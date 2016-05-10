package com.davfx.script;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExecutorScriptRunner implements ScriptRunner, AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(ExecutorScriptRunner.class);

	private ScriptEngine scriptEngine;
	private final ThreadPoolExecutor executorService; // = Executors.newSingleThreadExecutor(new ClassThreadFactory(ExecutorScriptRunner.class));
	
	public ExecutorScriptRunner() {
		executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

		doExecute(new Runnable() {
			@Override
			public void run() {
				scriptEngine = new ScriptEngineManager().getEngineByName("js");
				if (scriptEngine == null) {
					throw new IllegalArgumentException("Bad engine: js");
				}
				
				LOGGER.debug("Script engine {}/{}", scriptEngine.getFactory().getEngineName(), scriptEngine.getFactory().getEngineVersion());

				try {
					scriptEngine.eval(""
							
									+ "function registerSync(name, syncObject) {"
										+ "return function($, context, f) {"
											+ "var g = function(request) {"
												+ "return syncObject.call(request);"
											+ "};"
											+ "$[name] = g;"
											+ "return f();"
										+ "};"
									+ "}"
										
									+ "function registerAsync(name, asyncObject) {"
										+ "return function($, context, f) {"
											+ "var g = function(request, callback) {"
												+ "var capture = function(captureCallback, captureResponse) { captureCallback(captureResponse); };"
												+ "asyncObject.call(capture, context, request, callback);"
											+ "};"
											+ "$[name] = g;"
											+ "return f();"
										+ "};"
									+ "}"
										
									+ "function callCallback(capture, callback, response) {"
										+ "capture(callback, response);"
									+ "}"
										
									+ "function executeScript($, context, captures, index, script) {"
										+ "if ($ == null) {"
											+ "$ = {};"
										+ "}"
										+ "if (index == captures.size()) {"
											+ "eval(script);"
										+ "} else {"
											+ "var f = captures.get(index);"
											+ "return f($, context, function() {"
												+ "executeScript($, context, captures, index + 1, script);"
											+ "});"
										+ "}"
									+ "}"
										
									+ "");
				} catch (Exception se) {
					LOGGER.error("Could not initialize script engine", se);
				}
			}
		});
	}
	
	@Override
	public void close() {
		LOGGER.debug("Script engine closed");
		executorService.shutdown();
	}
	
	private static final class EndManager {
		private int count = 0;
		private End end;
		private boolean ended = false;
		public EndManager(End end) {
			this.end = end;
		}
		public boolean isEnded() {
			return ended;
		}
		public void fail(Exception e) {
			ended = true;
			LOGGER.error("Failed", e);
			End ee = end;
			end = null;
			if (ee != null) {
				ee.failed(e);
			}
		}
		public void inc() {
			count++;
		}
		public void dec() {
			count--;
			if (count == 0) {
				ended = true;
				End ee = end;
				end = null;
				if (ee != null) {
					ee.ended();
				}
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T, U> SyncScriptFunction<Object, Object> cast(SyncScriptFunction<T, U> f) {
		return (SyncScriptFunction<Object, Object>) f;
	}
	@SuppressWarnings("unchecked")
	private static <T, U> AsyncScriptFunction<Object, Object> cast(AsyncScriptFunction<T, U> f) {
		return (AsyncScriptFunction<Object, Object>) f;
	}
	
	// Must be be public to be called from javascript
	public final class SyncInternal {
		private final SyncScriptFunction<Object, Object> syncFunction;
		private SyncInternal(SyncScriptFunction<Object, Object> syncFunction) {
			this.syncFunction = syncFunction;
		}
		public Object call(Object requestAsObject) {
			return syncFunction.call(requestAsObject);
		}
	}
	
	// Must be be public to be called from javascript
	public final class AsyncInternal {
		private final AsyncScriptFunction<Object, Object> asyncFunction;
		private AsyncInternal(AsyncScriptFunction<Object, Object> asyncFunction) {
			this.asyncFunction = asyncFunction;
		}
		public void call(final Object capture, final EndManager endManager, Object requestAsObject, final Object callbackObject) {
			endManager.inc();
			
			asyncFunction.call(requestAsObject, new AsyncScriptFunction.Callback<Object>() {
				private boolean decCalled = false;
				/*%%% MEM LEAK!!!!
				@Override
				protected void finalize() {
					done();
				}
				*/
				@Override
				public void done() {
					doExecute(new Runnable() {
						@Override
						public void run() {
							if (decCalled) {
								return;
							}
							decCalled = true;
							endManager.dec();
						}
					});
				}
				@Override
				public void handle(final Object response) {
					doExecute(new Runnable() {
						@Override
						public void run() {
							if (endManager.isEnded()) {
								LOGGER.warn("Callback called on a terminated object");
								return;
							}

							try {
								((Invocable) scriptEngine).invokeFunction("callCallback", capture, callbackObject, response);
							} catch (Exception se) {
								LOGGER.error("Script callback fail ({})", callbackObject, se);
								endManager.fail(se);
							}
						}
					});
				}
			});
		}
	}
	
	private final class InnerEngine implements Engine {
		private final List<Object> captures = new ArrayList<>();
		private final List<String> functionNames = new ArrayList<>();
		
		public InnerEngine(final List<Object> initialCaptures, final List<String> initialFunctionNames) {
			doExecute(new Runnable() {
				@Override
				public void run() {
					if (initialCaptures != null) {
						captures.addAll(initialCaptures);
					}
					if (initialFunctionNames != null) {
						functionNames.addAll(initialFunctionNames);
					}
				}
			});
		}
		
		@Override
		public Engine sub() {
			return new InnerEngine(captures, functionNames);
		}
		
		@Override
		public <T, U> void register(final String function, final SyncScriptFunction<T, U> syncFunction) {
			doExecute(new Runnable() {
				@Override
				public void run() {
					functionNames.add(function);
					try {
						captures.add(((Invocable) scriptEngine).invokeFunction("registerSync", function, new SyncInternal(cast(syncFunction))));
					} catch (Exception se) {
						LOGGER.error("Script error", se);
					}
				}
			});
		}
		@Override
		public <T, U> void register(final String function, final AsyncScriptFunction<T, U> asyncFunction) {
			doExecute(new Runnable() {
				@Override
				public void run() {
					functionNames.add(function);
					try {
						captures.add(((Invocable) scriptEngine).invokeFunction("registerAsync", function, new AsyncInternal(cast(asyncFunction))));
					} catch (Exception se) {
						LOGGER.error("Script error", se);
					}
				}
			});
		}
		
		@Override
		public void eval(final String script, final End end) {
			doExecute(new Runnable() {
				@Override
				public void run() {
					StringBuilder b = new StringBuilder();
					b.append("(function() {");
					for (String function : functionNames) {
						b.append("var ").append(function).append(" = $['" + function + "'];");
					}
					b.append(script);
					b.append("})();");
					
					String composedScript = b.toString();
					
					EndManager endManager = new EndManager(end);
					endManager.inc();
					try {
						try {
							((Invocable) scriptEngine).invokeFunction("executeScript", null, endManager, captures, 0, composedScript);
						} catch (Exception se) {
							LOGGER.error("Script error", se);
							endManager.fail(se);
						}
					} finally {
						endManager.dec();
					}
				}
			});
		}
	}
	
	@Override
	public Engine engine() {
		return new InnerEngine(null, null);
	}

	private void doExecute(Runnable r) {
		int queueSize = executorService.getQueue().size();
		LOGGER.debug("Queue size = {}", queueSize);
		try {
			executorService.execute(r);
		} catch (RejectedExecutionException ree) {
		}
	}
}

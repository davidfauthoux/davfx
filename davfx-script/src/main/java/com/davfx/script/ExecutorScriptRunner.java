package com.davfx.script;

import java.util.LinkedList;
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
							
									+ "function registerSync($, name, syncObject) {"
										+ "if (!$) {"
											+ "$ = {};"
										+ "}"
										+ "$[name + '_'] = function(endManager) {"
											+ "return function(request) {"
												+ "return syncObject.call(request);"
											+ "};"
										+ "};"
										+ "return $;"
									+ "}"
										
									+ "function registerAsync($, name, asyncObject) {"
										+ "if (!$) {"
											+ "$ = {};"
										+ "}"
										+ "$[name + '_'] = function(endManager) {"
											+ "return function(request, callback) {"
												+ "asyncObject.call(endManager, request, callback);"
											+ "};"
										+ "};"
										+ "return $;"
									+ "}"
										
									+ "function callCallback(callback, response) {"
										+ "if (callback) {"
											+ "callback(response);"
										+ "}"
									+ "}"

									+ "function copyContext($) {"
										+ "if (!$) {"
											+ "return null;"
										+ "}"
										+ "var c = {};"
										+ "for (var k in $) {"
											+ "c[k] = $[k];"
										+ "}"
										+ "return c;"
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
		public void call(final EndManager endManager, Object requestAsObject, final Object callbackObject) {
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
								((Invocable) scriptEngine).invokeFunction("callCallback", new Object[] { callbackObject, response });
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
		private Object context = null;
		private final List<String> functions = new LinkedList<>();
		
		public InnerEngine(final InnerEngine parent) {
			if (parent != null) {
				doExecute(new Runnable() {
					@Override
					public void run() {
						functions.addAll(parent.functions);
						try {
							InnerEngine.this.context = ((Invocable) scriptEngine).invokeFunction("copyContext", new Object[] { parent.context });
						} catch (Exception se) {
							LOGGER.error("Script error", se);
						}
					}
				});
			}
		}
		
		@Override
		public Engine sub() {
			return new InnerEngine(this);
		}
		
		@Override
		public <T, U> void register(final String function, final SyncScriptFunction<T, U> syncFunction) {
			doExecute(new Runnable() {
				@Override
				public void run() {
					functions.add(function);
					try {
						context = ((Invocable) scriptEngine).invokeFunction("registerSync", new Object[] { context, function, new SyncInternal(cast(syncFunction)) });
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
					functions.add(function);
					try {
						context = ((Invocable) scriptEngine).invokeFunction("registerAsync", new Object[] { context, function, new AsyncInternal(cast(asyncFunction))});
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
					b.append("(function($, endManager) {\n"
							+ "if (!$) {\n"
								+ "$ = {};\n"
							+ "}\n");
					for (String f : functions) {
						b.append("var " + f + " = $." + f + "_(endManager);\n");
					}
					b.append("\n");
					b.append(script);
					b.append(";\n");
					b.append("\n");
					b.append("return $;\n"
							+ "})($, endManager);");
					
					String composedScript = b.toString();
					
					EndManager endManager = new EndManager(end);
					endManager.inc();
					try {
						try {
							try {
								scriptEngine.put("$", context);
								scriptEngine.put("endManager", endManager);
								context = scriptEngine.eval(composedScript);
							} finally {
								scriptEngine.put("endManager", null);
								scriptEngine.put("$", null);
							}
						} catch (Exception se) {
							LOGGER.error("Script error: {}", composedScript, se);
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
		return new InnerEngine(null);
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

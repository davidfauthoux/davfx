package com.davfx.script;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public final class ExecutorScriptRunner implements ScriptRunner, AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(ExecutorScriptRunner.class);

	private static final Config CONFIG = ConfigFactory.load(ExecutorScriptRunner.class.getClassLoader());
	
	private static final String UNICITY_PREFIX = CONFIG.getString("ninio.script.unicity.prefix");
	
	private ScriptEngine scriptEngine;
	private final ThreadPoolExecutor executorService; // = Executors.newSingleThreadExecutor(new ClassThreadFactory(ExecutorScriptRunner.class));

	public ExecutorScriptRunner() {
		executorService = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

		execute(new Runnable() {
			@Override
			public void run() {
				scriptEngine = new ScriptEngineManager().getEngineByName("js");
				if (scriptEngine == null) {
					throw new IllegalArgumentException("Bad engine: js");
				}
				
				LOGGER.debug("Script engine {}/{}", scriptEngine.getFactory().getEngineName(), scriptEngine.getFactory().getEngineVersion());
		
				try {
					scriptEngine.eval(""
							+ "var " + UNICITY_PREFIX + "context;\n"
							+ "var " + UNICITY_PREFIX + "nextUnicityId = 0;\n"
							+ "var " + UNICITY_PREFIX + "callbacks = {};\n"
						);
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
		public void call(final EndManager context, Object requestAsObject, final Object callbackObject) {
			context.inc();
			
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
					execute(new Runnable() {
						@Override
						public void run() {
							if (decCalled) {
								return;
							}
							decCalled = true;
							context.dec();
						}
					});
				}
				@Override
				public void handle(final Object response) {
					execute(new Runnable() {
						@Override
						public void run() {
							if (context.isEnded()) {
								LOGGER.warn("Callback called on a terminated object");
								return;
							}

							scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "callbackObject", callbackObject);
							try {
								scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "context", context);
								try {
									try {
										((Invocable) scriptEngine).invokeFunction(UNICITY_PREFIX + "callbackObject", response);
									} catch (Exception se) {
										LOGGER.error("Script callback fail", se);
										context.fail(se);
									}
								} finally {
									scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "context", null);
								}
							} finally {
								scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "callbackObject", null); // Memsafe null-set
								scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).remove(UNICITY_PREFIX + "callbackObject");
							}
						}
					});
				}
			});
		}
	}
	
	@Override
	public <T, U> void register(final String function, final SyncScriptFunction<T, U> syncFunction) {
		execute(new Runnable() {
			@Override
			public void run() {
				scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "function", new SyncInternal(cast(syncFunction)));
				try {
					try {
						scriptEngine.eval("var " + function + ";\n"
							+ "(function() {\n"
								+ "var " + UNICITY_PREFIX + "varfunction = " + UNICITY_PREFIX + "function;\n"
								+ function + " = function(p) {\n"
									+ "return " + UNICITY_PREFIX + "varfunction.call(p);\n"
								+ "};\n"
							+ "})();\n"
						);
					} catch (Exception se) {
						LOGGER.error("Could not register {}", function, se);
					}
				} finally {
					scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "function", null); // Memsafe null-set
					scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).remove(UNICITY_PREFIX + "function");
				}
			}
		});
	}
	
	@Override
	public <T, U> void register(final String function, final AsyncScriptFunction<T, U> asyncFunction) {
		execute(new Runnable() {
			@Override
			public void run() {
				scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "function", new AsyncInternal(cast(asyncFunction)));
				try {
					try {
						scriptEngine.eval("var " + function + ";\n"
							+ "(function() {\n"
								+ "var " + UNICITY_PREFIX + "varfunction = " + UNICITY_PREFIX + "function;\n"
								+ function + " = function(p, callback) {\n"
									+ UNICITY_PREFIX + "varfunction.call(" + UNICITY_PREFIX + "context, p, callback);\n"
								+ "};\n"
							+ "})();\n"
						);
					} catch (Exception se) {
						LOGGER.error("Could not register {}", function, se);
					}
				} finally {
					scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "function", null); // Memsafe null-set
					scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).remove(UNICITY_PREFIX + "function");
				}
			}
		});
	}

	@Override
	public void prepare(final String script, final ScriptRunner.End end) {
		execute(new Runnable() {
			@Override
			public void run() {
				EndManager endManager = new EndManager(end);
				endManager.inc();
				try {
					scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "context", endManager);
					try {
						try {
							scriptEngine.eval(script);
						} catch (Exception se) {
							LOGGER.error("Script error: {}", script, se);
							endManager.fail(se);
						}
					} finally {
						scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "context", null);
					}
				} finally {
					endManager.dec();
				}
			}
		});
	}
	
	@Override
	public Engine engine() {
		return new Engine() {
			private final Map<String, SyncScriptFunction<Object, Object>> syncFunctions = new LinkedHashMap<>();
			private final Map<String, AsyncScriptFunction<Object, Object>> asyncFunctions = new LinkedHashMap<>();
			@Override
			public <T, U> void register(String function, SyncScriptFunction<T, U> syncFunction) {
				syncFunctions.put(function, cast(syncFunction));
			}
			@Override
			public <T, U> void register(String function, AsyncScriptFunction<T, U> asyncFunction) {
				asyncFunctions.put(function, cast(asyncFunction));
			}
			
			@Override
			public void eval(final String script, final End end) {
				execute(new Runnable() {
					@Override
					public void run() {
						final List<String> bindingsToRemove = new LinkedList<>();
						EndManager endManager = new EndManager(end);
						endManager.inc();
						try {
							StringBuilder scriptBuilder = new StringBuilder();
							scriptBuilder.append("(function() {");
	
							for (Map.Entry<String, SyncScriptFunction<Object, Object>> e : syncFunctions.entrySet()) {
								String function = e.getKey();
								SyncScriptFunction<Object, Object> syncFunction = e.getValue();
								
								String functionObjectVar = UNICITY_PREFIX + "function_" + function;
								scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(functionObjectVar, new SyncInternal(syncFunction));
								bindingsToRemove.add(functionObjectVar);
								
								scriptBuilder.append("var ").append(function).append(";\n"
										+ "(function() {\n"
											+ "var " + UNICITY_PREFIX + "varfunction = ").append(functionObjectVar).append(";\n")
											.append(function).append(" = function(p) {\n"
												// + "return " + UNICITY_PREFIX + "convertTo(" + UNICITY_PREFIX + "varfunction.call(" + UNICITY_PREFIX + "convertFrom(p)));\n"
												+ "return " + UNICITY_PREFIX + "varfunction.call(p);\n"
											+ "};\n"
										+ "})();\n");
							}
	
							for (Map.Entry<String, AsyncScriptFunction<Object, Object>> e : asyncFunctions.entrySet()) {
								String function = e.getKey();
								AsyncScriptFunction<Object, Object> asyncFunction = e.getValue();
								
								String functionObjectVar = UNICITY_PREFIX + "function_" + function;
								scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(functionObjectVar, new AsyncInternal(asyncFunction));
								bindingsToRemove.add(functionObjectVar);

								scriptBuilder.append("var ").append(function).append(";\n"
										+ "(function() {\n"
											+ "var " + UNICITY_PREFIX + "varfunction = ").append(functionObjectVar).append(";\n")
											.append(function).append(" = function(p, callback) {\n"
												// + UNICITY_PREFIX + "varfunction.call(" + UNICITY_PREFIX + "context, " + UNICITY_PREFIX + "convertFrom(p), function(r) { callback(" + UNICITY_PREFIX + "convertTo(r)); });\n"
												+ UNICITY_PREFIX + "varfunction.call(" + UNICITY_PREFIX + "context, p, callback);\n"
											+ "};\n"
										+ "})();\n");
							}
							
							scriptBuilder.append(script);
							scriptBuilder.append("})();");
							
							String s = scriptBuilder.toString();
							try {
								scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "context", endManager);
								try {
									try {
										scriptEngine.eval(s);
									} catch (Exception se) {
										LOGGER.error("Script error: {}", s, se);
										endManager.fail(se);
									}
								} finally {
									scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "context", null);
								}
							} finally {
								for (String functionObjectVar : bindingsToRemove) {
									scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).remove(functionObjectVar);
								}
							}
						} finally {
							endManager.dec();
						}
					}
				});
			}
		};
	}

	private void execute(Runnable r) {
		int queueSize = executorService.getQueue().size();
		LOGGER.debug("Queue size = {}", queueSize);
		try {
			executorService.execute(r);
		} catch (RejectedExecutionException ree) {
		}
	}
}

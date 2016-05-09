package com.davfx.script;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.SimpleBindings;

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
	
	//%% private final Map<String, Object> globalClosure = new HashMap<>();

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

				/*%%
				try {
					scriptEngine.eval(""
							+ "var " + UNICITY_PREFIX + "context;\n"
						);
				} catch (Exception se) {
					LOGGER.error("Could not initialize script engine", se);
				}
				*/
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
	
	/*%%%
	private void executeScript(EndManager context, String script) {
		Map<String, Object> closure = scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE);
		closure.putAll(globalClosure);
		closure.putAll(context.closure);
		closure.put(UNICITY_PREFIX + "callbackObject", callbackObject);
		closure.put(UNICITY_PREFIX + "context", context);
		try {
			try {
				((Invocable) scriptEngine).invokeFunction(UNICITY_PREFIX + "callbackObject", response);
			} catch (Exception se) {
				LOGGER.error("Script callback fail", se);
				context.fail(se);
			}
		} finally {
			closure.clear();
		}

	}
	*/

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

							SimpleBindings bindings = new SimpleBindings();
							scriptEngine.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
							LOGGER.trace(">> Bindings = {}", scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).keySet());
							bindings.put(UNICITY_PREFIX + "callbackObject", callbackObject);
							bindings.put(UNICITY_PREFIX + "context", endManager);
							try {
								try {
									((Invocable) scriptEngine).invokeFunction(UNICITY_PREFIX + "callbackObject", response);
									LOGGER.trace("<< Bindings = {}", scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).keySet());
								} catch (Exception se) {
									LOGGER.error("Script callback fail", se);
									endManager.fail(se);
								}
							} finally {
								scriptEngine.setBindings(new SimpleBindings(), ScriptContext.ENGINE_SCOPE);
							}
						}
					});
				}
			});
		}
	}
	
	/*%%
	@Override
	public <T, U> void register(final String function, final SyncScriptFunction<T, U> syncFunction) {
		execute(new Runnable() {
			@Override
			public void run() {
				Map<String, Object> closure = scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE);
				closure.putAll(globalClosure);
				closure.put(UNICITY_PREFIX + "function", new SyncInternal(cast(syncFunction)));
				closure.put(UNICITY_PREFIX + "context", context);
				try {

				globalClosure.put(function, value)
				scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "function", );
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
	*/
	
	private final class InnerEngine implements Engine {
		private final SimpleBindings bindings = new SimpleBindings();
		
		public InnerEngine(final SimpleBindings initialBindings) {
			doExecute(new Runnable() {
				@Override
				public void run() {
					if (initialBindings != null) {
						LOGGER.debug("Bindings >>> {}", initialBindings.keySet());
						bindings.putAll(initialBindings);
					}
				}
			});
		}
		
		@Override
		public Engine sub() {
			return new InnerEngine(bindings);
		}
		
		@Override
		public <T, U> void register(final String function, final SyncScriptFunction<T, U> syncFunction) {
			doExecute(new Runnable() {
				@Override
				public void run() {
					StringBuilder scriptBuilder = new StringBuilder();
		
					String functionObjectVar = UNICITY_PREFIX + "function_" + function;
					bindings.put(functionObjectVar, new SyncInternal(cast(syncFunction)));
					
					scriptBuilder.append("var " + UNICITY_PREFIX + "functionVar_").append(function).append(" = ").append(functionObjectVar).append(";\n"
							+ "var ").append(function).append(" = function(p) {\n"
								+ "return " + UNICITY_PREFIX + "functionVar_").append(function).append(".call(p);\n"
							+ "};\n");
		
					String s = scriptBuilder.toString();
					
					scriptEngine.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
					try {
						try {
							scriptEngine.eval(s);
							LOGGER.debug("< Bindings = {}", scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).keySet());
						} catch (Exception se) {
							LOGGER.error("Script error: {}", s, se);
						}
					} finally {
						scriptEngine.setBindings(new SimpleBindings(), ScriptContext.ENGINE_SCOPE);
					}
				}
			});
		}
		@Override
		public <T, U> void register(final String function, final AsyncScriptFunction<T, U> asyncFunction) {
			doExecute(new Runnable() {
				@Override
				public void run() {
					StringBuilder scriptBuilder = new StringBuilder();
		
					String functionObjectVar = UNICITY_PREFIX + "function_" + function;
					bindings.put(functionObjectVar, new AsyncInternal(cast(asyncFunction)));
		
					scriptBuilder.append("var " + UNICITY_PREFIX + "functionVar_").append(function).append(" = ").append(functionObjectVar).append(";\n"
							+ "var ").append(function).append(" = function(p, callback) {\n"
								+ UNICITY_PREFIX + "functionVar_").append(function).append(".call(" + UNICITY_PREFIX + "context, p, callback);\n"
							+ "};\n");
		
					String s = scriptBuilder.toString();
					
					scriptEngine.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
					try {
						try {
							scriptEngine.eval(s);
							LOGGER.trace("< Bindings = {}", scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).keySet());
						} catch (Exception se) {
							LOGGER.error("Script error: {}", s, se);
						}
					} finally {
						scriptEngine.setBindings(new SimpleBindings(), ScriptContext.ENGINE_SCOPE);
					}
				}
			});
		}
		
		@Override
		public void eval(final String script, final End end) {
			doExecute(new Runnable() {
				@Override
				public void run() {
					EndManager endManager = new EndManager(end);
					endManager.inc();
					try {
						scriptEngine.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
						LOGGER.trace("> Bindings = {}", scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).keySet());

						bindings.put(UNICITY_PREFIX + "context", endManager);
						try {
							try {
								scriptEngine.eval(script);
								LOGGER.trace("< Bindings = {}", scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).keySet());
							} catch (Exception se) {
								LOGGER.error("Script error: {}", script, se);
								endManager.fail(se);
							}
						} finally {
							scriptEngine.setBindings(new SimpleBindings(), ScriptContext.ENGINE_SCOPE);
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

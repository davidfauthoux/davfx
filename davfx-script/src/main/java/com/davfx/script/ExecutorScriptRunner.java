package com.davfx.script;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.util.ClassThreadFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;

public final class ExecutorScriptRunner implements ScriptRunner, AutoCloseable {
	private static final Logger LOGGER = LoggerFactory.getLogger(ExecutorScriptRunner.class);

	private static final Config CONFIG = ConfigFactory.load(ExecutorScriptRunner.class.getClassLoader());
	
	private static final String ENGINE_NAME = CONFIG.getString("ninio.script.engine");
	static {
		LOGGER.debug("Engine: {}", ENGINE_NAME);
	}

	private static final boolean USE_TO_STRING;
	static {
		String mode = CONFIG.getString("ninio.script.mode");
		if (mode.equals("string")) {
			USE_TO_STRING = true;
			LOGGER.debug("Mode: string");
		} else if (mode.equals("json")) {
			USE_TO_STRING = false;
			LOGGER.debug("Mode: json");
		} else {
			throw new ConfigException.BadValue("script.mode", "Invalid mode, only allowed: json|string");
		}
	}
	
	private static final String UNICITY_PREFIX = CONFIG.getString("ninio.script.unicity.prefix");
	
	private ScriptEngine scriptEngine;
	private final ExecutorService executorService = Executors.newSingleThreadExecutor(new ClassThreadFactory(ExecutorScriptRunner.class));

	public ExecutorScriptRunner() {
		execute(new Runnable() {
			@Override
			public void run() {
				ScriptEngineManager scriptEngineManager = new ScriptEngineManager();
				
				scriptEngine = scriptEngineManager.getEngineByName(ENGINE_NAME);
				if (scriptEngine == null) {
					throw new IllegalArgumentException("Bad engine: " + ENGINE_NAME);
				}
				LOGGER.debug("Script engine {}/{}", scriptEngine.getFactory().getEngineName(), scriptEngine.getFactory().getEngineVersion());
		
				try {
					//%% scriptEngine.eval(ScriptUtils.functions());
					scriptEngine.eval(""
							+ "var " + UNICITY_PREFIX + "context;\n"
							+ "var " + UNICITY_PREFIX + "nextUnicityId = 0;\n"
							+ "var " + UNICITY_PREFIX + "callbacks = {};\n"
						);
					
					if (USE_TO_STRING) {
						scriptEngine.eval(""
								+ "var " + UNICITY_PREFIX + "convertFrom = function(o) { if (!o) return null; return JSON.stringify(o); };\n"
								+ "var " + UNICITY_PREFIX + "convertTo = function(o) { if (!o) return null; return JSON.parse(o); };\n"
							);
					} else {
						scriptEngine.eval(""
								+ "var " + UNICITY_PREFIX + "convertFrom = function(o) {"
									+ "if (!o) {"
										+ "return null;"
									+ "}"
									+ "if (o instanceof Array) {"
										+ "var p = new com.google.gson.JsonArray();"
										+ "for (k in o) {"
											+ "p.add(" + UNICITY_PREFIX + "convertFrom(o[k]));"
										+ "}"
										+ "return p;"
									+ "}"
									+ "if (o instanceof Object) {"
										+ "var p = new com.google.gson.JsonObject();"
										+ "for (k in o) {"
											+ "p.add(k, " + UNICITY_PREFIX + "convertFrom(o[k]));"
										+ "}"
										+ "return p;"
									+ "}"
									+ "if (typeof o == \"string\") {"
										+ "return " + ExecutorScriptRunner.class.getName() + ".jsonString(o);"
									+ "}"
									+ "if (typeof o == \"number\") {"
										+ "return " + ExecutorScriptRunner.class.getName() + ".jsonNumber(o);"
									+ "}"
									+ "if (typeof o == \"boolean\") {"
										+ "return " + ExecutorScriptRunner.class.getName() + ".jsonBoolean(o);"
									+ "}"
								+ "};\n"
							+ "var " + UNICITY_PREFIX + "convertTo = function(o) {"
								+ "if (!o) {"
									+ "return null;"
								+ "}"
								+ "if (o.isJsonObject()) {"
									+ "var i = o.entrySet().iterator();"
									+ "var p = {};"
									+ "while (i.hasNext()) {"
										+ "var e = i.next();"
										+ "p[e.getKey()] = " + UNICITY_PREFIX + "convertTo(e.getValue());"
									+ "}"
									+ "return p;"
								+ "}"
								+ "if (o.isJsonPrimitive()) {"
									+ "var oo = o.getAsJsonPrimitive();"
									+ "if (oo.isString()) {"
										+ "return '' + oo.getAsString();" // ['' +] looks to be necessary
									+ "}"
									+ "if (oo.isNumber()) {"
										+ "return oo.getAsDouble();" //TODO Check long precision??? 
									+ "}"
									+ "if (oo.isBoolean()) {"
										+ "return oo.getAsBoolean();"
									+ "}"
									+ "return null;"
								+ "}"
								+ "return null;"
							+ "};\n");
					}
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
	
	// Must be be public to be called from javascript
	public final class SyncInternal {
		private final SyncScriptFunction syncFunction;
		private SyncInternal(SyncScriptFunction syncFunction) {
			this.syncFunction = syncFunction;
		}
		public Object call(Object requestAsObject) {
			JsonElement request;
			if (USE_TO_STRING) {
				request = (requestAsObject == null) ? null : new JsonParser().parse((String) requestAsObject);
			} else {
				request = (JsonElement) requestAsObject;
			}
			
			JsonElement response = syncFunction.call(request);

			if (USE_TO_STRING) {
				return (response == null) ? "null" : response.toString();
			} else {
				return response;
			}
		}
	}
	
	// Must be be public to be called from javascript
	public final class AsyncInternal {
		private final AsyncScriptFunction asyncFunction;
		private AsyncInternal(AsyncScriptFunction asyncFunction) {
			this.asyncFunction = asyncFunction;
		}
		public void call(final EndManager context, Object requestAsObject, final Object callbackObject) {
			JsonElement request;
			if (USE_TO_STRING) {
				request = (requestAsObject == null) ? null : new JsonParser().parse((String) requestAsObject);
			} else {
				request = (JsonElement) requestAsObject;
			}

			context.inc();
			
			asyncFunction.call(request, new AsyncScriptFunction.Callback() {
				private boolean decCalled = false;
				@Override
				protected void finalize() {
					done();
				}
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
				public void handle(final JsonElement response) {
					execute(new Runnable() {
						@Override
						public void run() {
							if (context.isEnded()) {
								LOGGER.warn("Callback called on a terminated object");
								return;
							}

							if (!USE_TO_STRING) {
								scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "response", response);
							}
							scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "callbackObject", callbackObject);
							try {
								String script = "(function() {\n"
									+ "var " + UNICITY_PREFIX + "f = " + UNICITY_PREFIX + "callbackObject;\n"
									+ (USE_TO_STRING ?
											"var " + UNICITY_PREFIX + "r = " + ((response == null) ? "null" : new JsonPrimitive(response.toString()).toString()) + ";\n"
											:
											"var " + UNICITY_PREFIX + "r = " + UNICITY_PREFIX + "response;\n"
										)
									+ UNICITY_PREFIX + "f(" + UNICITY_PREFIX + "r);\n"
									+ "})();\n";
								scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "context", context);
								try {
									try {
										scriptEngine.eval(script);
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
								if (!USE_TO_STRING) {
									scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "response", null); // Memsafe null-set
									scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).remove(UNICITY_PREFIX + "response");
								}
							}
						}
					});
				}
			});
		}
	}
	
	@Override
	public void register(final String function, final SyncScriptFunction syncFunction) {
		execute(new Runnable() {
			@Override
			public void run() {
				scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "function", new SyncInternal(syncFunction));
				try {
					try {
						scriptEngine.eval("var " + function + ";\n"
							+ "(function() {\n"
								+ "var " + UNICITY_PREFIX + "varfunction = " + UNICITY_PREFIX + "function;\n"
								+ function + " = function(p) {\n"
									+ "return " + UNICITY_PREFIX + "convertTo(" + UNICITY_PREFIX + "varfunction.call(" + UNICITY_PREFIX + "convertFrom(p)));\n"
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
	public void register(final String function, final AsyncScriptFunction asyncFunction) {
		execute(new Runnable() {
			@Override
			public void run() {
				scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(UNICITY_PREFIX + "function", new AsyncInternal(asyncFunction));
				try {
					try {
						scriptEngine.eval("var " + function + ";\n"
							+ "(function() {\n"
								+ "var " + UNICITY_PREFIX + "varfunction = " + UNICITY_PREFIX + "function;\n"
								+ function + " = function(p, callback) {\n"
									+ UNICITY_PREFIX + "varfunction.call(" + UNICITY_PREFIX + "context, " + UNICITY_PREFIX + "convertFrom(p), function(r) { callback(" + UNICITY_PREFIX + "convertTo(r)); });\n"
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
			private final Map<String, SyncScriptFunction> syncFunctions = new LinkedHashMap<>();
			private final Map<String, AsyncScriptFunction> asyncFunctions = new LinkedHashMap<>();
			@Override
			public void register(String function, SyncScriptFunction syncFunction) {
				syncFunctions.put(function, syncFunction);
			}
			@Override
			public void register(String function, AsyncScriptFunction asyncFunction) {
				asyncFunctions.put(function, asyncFunction);
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
	
							for (Map.Entry<String, SyncScriptFunction> e : syncFunctions.entrySet()) {
								String function = e.getKey();
								SyncScriptFunction syncFunction = e.getValue();
								
								String functionObjectVar = UNICITY_PREFIX + "function_" + function;
								scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(functionObjectVar, new SyncInternal(syncFunction));
								bindingsToRemove.add(functionObjectVar);
								
								scriptBuilder.append("var ").append(function).append(";\n"
										+ "(function() {\n"
											+ "var " + UNICITY_PREFIX + "varfunction = ").append(functionObjectVar).append(";\n")
											.append(function).append(" = function(p) {\n"
												+ "return " + UNICITY_PREFIX + "convertTo(" + UNICITY_PREFIX + "varfunction.call(" + UNICITY_PREFIX + "convertFrom(p)));\n"
											+ "};\n"
										+ "})();\n");
							}
	
							for (Map.Entry<String, AsyncScriptFunction> e : asyncFunctions.entrySet()) {
								String function = e.getKey();
								AsyncScriptFunction asyncFunction = e.getValue();
								
								String functionObjectVar = UNICITY_PREFIX + "function_" + function;
								scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE).put(functionObjectVar, new AsyncInternal(asyncFunction));
								bindingsToRemove.add(functionObjectVar);

								scriptBuilder.append("var ").append(function).append(";\n"
										+ "(function() {\n"
											+ "var " + UNICITY_PREFIX + "varfunction = ").append(functionObjectVar).append(";\n")
											.append(function).append(" = function(p, callback) {\n"
													+ UNICITY_PREFIX + "varfunction.call(" + UNICITY_PREFIX + "context, " + UNICITY_PREFIX + "convertFrom(p), function(r) { callback(" + UNICITY_PREFIX + "convertTo(r)); });\n"
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

	// Must be be public to be called from javascript
	public static JsonElement jsonString(String b) {
		return new JsonPrimitive(b);
	}
	// Must be be public to be called from javascript
	public static JsonElement jsonNumber(Number b) {
		return new JsonPrimitive(b);
	}
	// Must be be public to be called from javascript
	public static JsonElement jsonBoolean(boolean b) {
		return new JsonPrimitive(b);
	}
	
	private void execute(Runnable r) {
		try {
			executorService.execute(r);
		} catch (RejectedExecutionException ree) {
		}
	}
}

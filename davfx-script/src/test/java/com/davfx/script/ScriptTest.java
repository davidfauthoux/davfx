package com.davfx.script;

import java.util.HashMap;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.util.Lock;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

public class ScriptTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(ScriptTest.class);
	
	@Test
	public void testSimpleSync() throws Exception {
		try (ScriptRunner runner = new ExecutorScriptRunner("js")) {
			final Lock<Object, Exception> lock = new Lock<>();
			
			runner.register("syncEcho1", new SyncScriptFunction<Map<String, String>, Map<String, String>>() {
				@Override
				public Map<String, String> call(Map<String,String> request) {
					LOGGER.debug("1 -------> {}", request);
					Map<String, String> m = new HashMap<>();
					m.put("out", "cc");
					return m;
				}
			});
			
			runner.register("syncEcho2", new SyncScriptFunction<Map<String, String>, Map<String, String>>() {
				@Override
				public Map<String, String> call(Map<String,String> request) {
					LOGGER.debug("2 -------> {}", request);
					lock.set(request.get("out"));
					return request;
				}
			});
			
			ScriptRunner.Engine engine = runner.engine();
			engine.eval("var echoed = syncEcho2(syncEcho1({'message':'bb'}));", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("End");
				}
			});
			
			Assertions.assertThat(lock.waitFor().toString()).isEqualTo("cc");
		}
	}

	@Test
	public void testSync() throws Exception {
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			final Lock<Object, Exception> lock = new Lock<>();
			
			runner.register("syncEcho", new SyncScriptFunction() {
				@Override
				public Object call(Object request) {
					JsonObject o = new JsonObject();
					o.add("message", new JsonPrimitive("synchEcho " + ((JsonElement) request).getAsJsonObject().get("message").getAsString()));
					return o;
				}
			});
			runner.prepare("var echoed = syncEcho({'message':'aa'});", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("syncEcho end");
				}
			});
			
			ScriptRunner.Engine engine = runner.engine();
			engine.register("syncEcho2", new SyncScriptFunction() {
				@Override
				public Object call(Object request) {
					JsonObject o = new JsonObject();
					o.add("message", new JsonPrimitive("synchEcho2 " + ((JsonElement) request).getAsJsonObject().get("message").getAsString()));
					return o;
				}
			});
			engine.register("out", new SyncScriptFunction() {
				@Override
				public Object call(Object request) {
					lock.set(request);
					return null;
				}
			});
			engine.eval("var echoed2 = syncEcho2({'message':'bb'});", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("syncEcho2 end");
				}
			});
			engine.eval("out(syncEcho(syncEcho2({'message':'bb'})));", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("eval end");
				}
			});
			
			Assertions.assertThat(lock.waitFor().toString()).isEqualTo("{\"message\":\"synchEcho synchEcho2 bb\"}");
		}
	}

	@Test
	public void testAsync() throws Exception {
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			final Lock<Object, Exception> lock = new Lock<>();
			
			runner.register("asyncEcho", new AsyncScriptFunction() {
				@Override
				public void call(Object request, Callback callback) {
					JsonObject o = new JsonObject();
					o.add("message", new JsonPrimitive("asynchEcho " + ((JsonElement) request).getAsJsonObject().get("message").getAsString()));
					callback.handle(o);
				}
			});
			runner.prepare("asyncEcho({'message':'aa'}, function(r) { });", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("asyncEcho end");
				}
			});
			
			ScriptRunner.Engine engine = runner.engine();
			engine.register("asyncEcho2", new AsyncScriptFunction() {
				@Override
				public void call(Object request, Callback callback) {
					JsonObject o = new JsonObject();
					o.add("message", new JsonPrimitive("asynchEcho2 " + ((JsonElement) request).getAsJsonObject().get("message").getAsString()));
					callback.handle(o);
				}
			});
			engine.register("out", new SyncScriptFunction() {
				@Override
				public Object call(Object request) {
					lock.set(request);
					return null;
				}
			});
			engine.eval("asyncEcho2({'message':'bb'}, function(r) { });", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("asyncEcho2 end");
				}
			});
			engine.eval("asyncEcho2({'message':'bb'}, function(r) { asyncEcho(r, function(r2) { out(r2); }); });", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("eval end");
				}
			});
			
			Assertions.assertThat(lock.waitFor().toString()).isEqualTo("{\"message\":\"asynchEcho asynchEcho2 bb\"}");
		}
	}

	@Test
	public void testError() throws Exception {
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			final Lock<String, Exception> lock = new Lock<>();
			
			ScriptRunner.Engine engine = runner.engine();
			engine.eval("err;", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.set(e.getMessage());
				}
				@Override
				public void ended() {
					Assertions.fail("ended should not be called");
					lock.set(null);
				}
			});
			
			Assertions.assertThat(lock.waitFor().toString()).isNotNull();
		}
	}

}

package com.davfx.script;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.util.Lock;
import com.google.gson.JsonElement;

public class ContextTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(ContextTest.class);
	
	@Test
	public void test() throws Exception {
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			final Lock<JsonElement, Exception> lock = new Lock<>();
			final Lock<Boolean, Exception> endLock = new Lock<>();
			
			runner.register("trace", new SyncScriptFunction() {
				@Override
				public JsonElement call(JsonElement request) {
					LOGGER.debug("TRACE {}", request);
					lock.set(request);
					return null;
				}
			});
			runner.register("echo", new AsyncScriptFunction() {
				@Override
				public void call(JsonElement request, Callback callback) {
					LOGGER.debug("ECHO {}", request);
					callback.handle(request);
					callback.done();
				}
			});
			runner.prepare("var f = function(a, c) { echo(a, c); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("f end");
				}
			});
			
			ScriptRunner.Engine engine = runner.engine();
			engine.eval("f('aaa', function(r) { trace(r); });", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
					endLock.set(true);
				}
				@Override
				public void ended() {
					LOGGER.debug("f('aaa') end");
					endLock.set(true);
				}
			});
			
			Assertions.assertThat(endLock.waitFor()).isTrue();
			Assertions.assertThat(lock.waitFor().getAsString()).isEqualTo("aaa");
		}
	}

	@Test
	public void test2() throws Exception {
		final Executor e = Executors.newSingleThreadExecutor();
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			final Lock<JsonElement, Exception> lock = new Lock<>();
			final Lock<Boolean, Exception> endLock = new Lock<>();
			
			runner.register("trace", new SyncScriptFunction() {
				@Override
				public JsonElement call(JsonElement request) {
					LOGGER.debug("TRACE {}", request);
					lock.set(request);
					return null;
				}
			});
			runner.register("echo", new AsyncScriptFunction() {
				@Override
				public void call(final JsonElement request, final Callback callback) {
					e.execute(new Runnable() {
						@Override
						public void run() {
							try {
								Thread.sleep(100);
							} catch (InterruptedException ie) {
							}
							LOGGER.debug("ECHO {}", request);
							callback.handle(request);
							callback.done();
						}
					});
				}
			});
			runner.prepare("var f = function(a, c) { echo(a, c); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("f end");
				}
			});
			
			ScriptRunner.Engine engine = runner.engine();
			engine.register("echo2", new AsyncScriptFunction() {
				@Override
				public void call(final JsonElement request, final Callback callback) {
					e.execute(new Runnable() {
						@Override
						public void run() {
							try {
								Thread.sleep(100);
							} catch (InterruptedException ie) {
							}
							LOGGER.debug("ECHO2 {}", request);
							callback.handle(request);
							callback.done();
						}
					});
				}
			});
			engine.register("echo3", new SyncScriptFunction() {
				@Override
				public JsonElement call(JsonElement request) {
					LOGGER.debug("ECHO3 {}", request);
					return request;
				}
			});
			engine.eval("f('aaa', function(r) { echo3(r); echo2(r, function(rr) { trace(echo3(rr)); }); });", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
					endLock.set(true);
				}
				@Override
				public void ended() {
					LOGGER.debug("f('aaa') end");
					endLock.set(true);
				}
			});
			
			Assertions.assertThat(endLock.waitFor()).isTrue();
			Assertions.assertThat(lock.waitFor().getAsString()).isEqualTo("aaa");
		}
	}
	
	@Test
	public void test3() throws Exception {
		final Executor e = Executors.newSingleThreadExecutor();
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			final Lock<JsonElement, Exception> lock = new Lock<>();
			final Lock<Boolean, Exception> endLock = new Lock<>();
			
			runner.register("trace", new SyncScriptFunction() {
				@Override
				public JsonElement call(JsonElement request) {
					LOGGER.debug("TRACE {}", request);
					lock.set(request);
					return null;
				}
			});
			runner.register("echo", new AsyncScriptFunction() {
				@Override
				public void call(final JsonElement request, final Callback callback) {
					e.execute(new Runnable() {
						@Override
						public void run() {
							try {
								Thread.sleep(100);
							} catch (InterruptedException ie) {
							}
							LOGGER.debug("ECHO {}", request);
							callback.handle(request);
							callback.done();
						}
					});
				}
			});
			runner.prepare("var echo3$; var f = function(a, c) { echo(echo3$(a), c); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("f end");
				}
			});
			
			ScriptRunner.Engine engine = runner.engine();
			engine.register("echo2", new AsyncScriptFunction() {
				@Override
				public void call(final JsonElement request, final Callback callback) {
					e.execute(new Runnable() {
						@Override
						public void run() {
							try {
								Thread.sleep(100);
							} catch (InterruptedException ie) {
							}
							LOGGER.debug("ECHO2 {}", request);
							callback.handle(request);
							callback.done();
						}
					});
				}
			});
			engine.register("echo3", new SyncScriptFunction() {
				@Override
				public JsonElement call(JsonElement request) {
					LOGGER.debug("ECHO3 {}", request);
					return request;
				}
			});
			engine.eval("echo3$ = echo3; f('aaa', function(r) { echo3(r); echo2(r, function(rr) { trace(echo3(rr)); }); });", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
					endLock.set(true);
				}
				@Override
				public void ended() {
					LOGGER.debug("f('aaa') end");
					endLock.set(true);
				}
			});
			
			Assertions.assertThat(endLock.waitFor()).isTrue();
			Assertions.assertThat(lock.waitFor().getAsString()).isEqualTo("aaa");
		}
	}
	
	@Test
	public void test4() throws Exception {
		final Executor e = Executors.newSingleThreadExecutor();
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			final Lock<JsonElement, Exception> lock0 = new Lock<>();
			final Lock<Boolean, Exception> endLock0 = new Lock<>();
			final Lock<JsonElement, Exception> lock1 = new Lock<>();
			final Lock<Boolean, Exception> endLock1 = new Lock<>();
			
			runner.prepare("var context;", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					lock1.fail(e);
				}
				@Override
				public void ended() {
				}
			});
			runner.register("echo", new AsyncScriptFunction() {
				@Override
				public void call(final JsonElement request, final Callback callback) {
					e.execute(new Runnable() {
						@Override
						public void run() {
							try {
								Thread.sleep(100);
							} catch (InterruptedException ie) {
							}
							LOGGER.debug("ECHO {}", request);
							callback.handle(request);
							callback.done();
						}
					});
				}
			});
			runner.prepare("var f = function(a, c) { var _context = context; echo(a, function(aa) { _context.out(aa); c(); }); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					lock1.fail(e);
				}
				@Override
				public void ended() {
				}
			});
			
			ScriptRunner.Engine engine0 = runner.engine();
			engine0.register("out", new SyncScriptFunction() {
				@Override
				public JsonElement call(JsonElement request) {
					LOGGER.debug("Out0 {}", request);
					lock0.set(request);
					return request;
				}
			});
			engine0.eval("context = { out: out }; f('aaa0', function() {});", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					endLock0.set(true);
				}
				@Override
				public void ended() {
					LOGGER.debug("f('aaa0') end");
					endLock0.set(true);
				}
			});

			ScriptRunner.Engine engine1 = runner.engine();
			engine1.register("out", new SyncScriptFunction() {
				@Override
				public JsonElement call(JsonElement request) {
					LOGGER.debug("Out1 {}", request);
					lock1.set(request);
					return request;
				}
			});
			engine1.eval("context = { out: out }; f('aaa1', function() {});", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock1.fail(e);
					endLock1.set(true);
				}
				@Override
				public void ended() {
					LOGGER.debug("f('aaa1') end");
					endLock1.set(true);
				}
			});
			
			Assertions.assertThat(endLock0.waitFor()).isTrue();
			Assertions.assertThat(endLock1.waitFor()).isTrue();
			Assertions.assertThat(lock0.waitFor().getAsString()).isEqualTo("aaa0");
			Assertions.assertThat(lock1.waitFor().getAsString()).isEqualTo("aaa1");
		}
	}
	
	@Test
	public void test5() throws Exception {
		final Executor e = Executors.newSingleThreadExecutor();
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			final Lock<JsonElement, Exception> lock0 = new Lock<>();
			final Lock<Boolean, Exception> endLock0 = new Lock<>();
			final Lock<JsonElement, Exception> lock1 = new Lock<>();
			final Lock<Boolean, Exception> endLock1 = new Lock<>();
			
			runner.prepare("var context;", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					lock1.fail(e);
				}
				@Override
				public void ended() {
				}
			});
			runner.register("echo", new AsyncScriptFunction() {
				@Override
				public void call(final JsonElement request, final Callback callback) {
					e.execute(new Runnable() {
						@Override
						public void run() {
							try {
								Thread.sleep(100);
							} catch (InterruptedException ie) {
							}
							LOGGER.debug("ECHO {}", request);
							callback.handle(request);
							callback.done();
						}
					});
				}
			});
			runner.prepare("var f = function(a, c) { var _context = context; echo(a, function(aa) { _context.out(aa); c(); }); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					lock1.fail(e);
				}
				@Override
				public void ended() {
				}
			});
			
			ScriptRunner.Engine engine0 = runner.engine();
			engine0.register("out", new SyncScriptFunction() {
				@Override
				public JsonElement call(JsonElement request) {
					LOGGER.debug("Out0 {}", request);
					lock0.set(request);
					return request;
				}
			});
			engine0.eval("context = { out: out }; f('aaa0', function() {}); f('aaa0', function() {});", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					endLock0.set(true);
				}
				@Override
				public void ended() {
					LOGGER.debug("f('aaa0') end");
					endLock0.set(true);
				}
			});

			ScriptRunner.Engine engine1 = runner.engine();
			engine1.register("out", new SyncScriptFunction() {
				@Override
				public JsonElement call(JsonElement request) {
					LOGGER.debug("Out1 {}", request);
					lock1.set(request);
					return request;
				}
			});
			engine1.eval("context = { out: out }; f('aaa1', function() {}); f('aaa1', function() {});", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock1.fail(e);
					endLock1.set(true);
				}
				@Override
				public void ended() {
					LOGGER.debug("f('aaa1') end");
					endLock1.set(true);
				}
			});
			
			Assertions.assertThat(endLock0.waitFor()).isTrue();
			Assertions.assertThat(endLock1.waitFor()).isTrue();
			Assertions.assertThat(lock0.waitFor().getAsString()).isEqualTo("aaa0");
			Assertions.assertThat(lock1.waitFor().getAsString()).isEqualTo("aaa1");
		}
	}
}

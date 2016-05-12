package com.davfx.script;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.assertj.core.api.Assertions;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.util.Lock;

public class ContextTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(ContextTest.class);
	
	@Ignore
	@Test
	public void test() throws Exception {
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			ScriptRunner.Engine engine = runner.engine();
			final Lock<Object, Exception> lock = new Lock<>();
			final Lock<Boolean, Exception> endLock = new Lock<>();
			
			engine.register("trace", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
					LOGGER.debug("TRACE {}", request);
					lock.set(request);
					return null;
				}
			});
			engine.register("echo", new AsyncScriptFunction<Object, Object>() {
				@Override
				public void call(Object request, AsyncScriptFunction.Callback<Object> callback) {
					LOGGER.debug("ECHO {}", request);
					callback.handle(request);
					callback.done();
				}
			});
			engine.eval("$.fffff = function(a, c) { echo(a, c); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("f end");
				}
			});
			
			engine.eval("$.fffff('aaa', function(r) { trace(r); });", new ScriptRunner.End() {
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
			Assertions.assertThat(lock.waitFor()).isEqualTo("aaa");
			LOGGER.debug("---");
		}
	}

	@Ignore
	@Test
	public void test2() throws Exception {
		final Executor e = Executors.newSingleThreadExecutor();
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			ScriptRunner.Engine engine = runner.engine();
			final Lock<Object, Exception> lock = new Lock<>();
			final Lock<Boolean, Exception> endLock = new Lock<>();
			
			engine.register("trace", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
					LOGGER.debug("TRACE {}", request);
					lock.set(request);
					return null;
				}
			});
			engine.register("echo", new AsyncScriptFunction<Object, Object>() {
				@Override
				public void call(Object request, AsyncScriptFunction.Callback<Object> callback) {
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
			engine.eval("$.f = function(a, c) { echo(a, c); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("f end");
				}
			});
			
			engine.register("echo2", new AsyncScriptFunction<Object, Object>() {
				@Override
				public void call(final Object request, final AsyncScriptFunction.Callback<Object> callback) {
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
			engine.register("echo3", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
					LOGGER.debug("ECHO3 {}", request);
					return request;
				}
			});
			engine.eval("$.f('aaa', function(r) { echo3(r); echo2(r, function(rr) { trace(echo3(rr)); }); });", new ScriptRunner.End() {
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
			Assertions.assertThat(lock.waitFor()).isEqualTo("aaa");
		}
	}
	
	@Test
	public void test3() throws Exception {
		final Executor e = Executors.newSingleThreadExecutor();
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			ScriptRunner.Engine engine = runner.engine();
			final Lock<Object, Exception> lock = new Lock<>();
			final Lock<Boolean, Exception> endLock = new Lock<>();
			
			engine.register("trace", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
					LOGGER.debug("TRACE {}", request);
					lock.set(request);
					return null;
				}
			});
			engine.register("echo", new AsyncScriptFunction<Object, Object>() {
				@Override
				public void call(final Object request, final Callback<Object> callback) {
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
			engine.eval("var echo3$; var f = function(a, c) { echo(echo3$(a), c); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock.fail(e);
				}
				@Override
				public void ended() {
					LOGGER.debug("f end");
				}
			});
			
			engine.register("echo2", new AsyncScriptFunction<Object, Object>() {
				@Override
				public void call(final Object request, final Callback<Object> callback) {
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
			engine.register("echo3", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
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
			Assertions.assertThat(lock.waitFor()).isEqualTo("aaa");
		}
	}
	
	@Test
	public void test40() throws Exception {
		final Executor e = Executors.newSingleThreadExecutor();
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			ScriptRunner.Engine engine = runner.engine();
			final Lock<Object, Exception> lock0 = new Lock<>();
			final Lock<Boolean, Exception> endLock0 = new Lock<>();
			final Lock<Object, Exception> lock1 = new Lock<>();
			final Lock<Boolean, Exception> endLock1 = new Lock<>();
			
			engine.eval("var context;", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					lock1.fail(e);
				}
				@Override
				public void ended() {
				}
			});
			engine.register("echo", new AsyncScriptFunction<Object, Object>() {
				@Override
				public void call(final Object request, final Callback<Object> callback) {
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
			engine.eval("var f = function(c) { echo(context, c); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					lock1.fail(e);
				}
				@Override
				public void ended() {
				}
			});
			
			ScriptRunner.Engine engine0 = engine.sub();
			engine0.register("out", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
					LOGGER.debug("Out0 {}", request);
					lock0.set(request);
					return request;
				}
			});

			ScriptRunner.Engine engine1 = engine.sub();
			engine1.register("out", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
					LOGGER.debug("Out1 {}", request);
					lock1.set(request);
					return request;
				}
			});

			engine0.eval("context = '000'; var toto='titi'; f(function(r) { out(r); });", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					endLock0.set(true);
				}
				@Override
				public void ended() {
					LOGGER.debug("000 end");
					endLock0.set(true);
				}
			});
			
			Assertions.assertThat(endLock0.waitFor()).isTrue();
			Assertions.assertThat(endLock1.waitFor()).isTrue();
			Assertions.assertThat(lock0.waitFor()).isEqualTo("000");
			Assertions.assertThat(lock1.waitFor()).isEqualTo("111");
		}
	}
	
	@Test
	public void test41() throws Exception {
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			ScriptRunner.Engine engine = runner.engine();
			final Lock<Object, Exception> lock0 = new Lock<>();
			final Lock<Boolean, Exception> endLock0 = new Lock<>();
			final Lock<Object, Exception> lock1 = new Lock<>();
			final Lock<Boolean, Exception> endLock1 = new Lock<>();
			
			ScriptRunner.Engine engine0 = engine.sub();
			engine0.register("out", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
					LOGGER.debug("Out0 {}", request);
					lock0.set(request);
					return request;
				}
			});

			ScriptRunner.Engine engine1 = engine.sub();
			engine1.register("out", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
					LOGGER.debug("Out1 {}", request);
					lock1.set(request);
					return request;
				}
			});
			
			engine0.eval("out('aaa');", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					endLock0.set(true);
				}
				@Override
				public void ended() {
					LOGGER.debug("End");
					endLock0.set(true);
				}
			});
			
			Assertions.assertThat(endLock0.waitFor()).isTrue();
			Assertions.assertThat(endLock1.waitFor()).isTrue();
			Assertions.assertThat(lock0.waitFor()).isEqualTo("000");
			Assertions.assertThat(lock1.waitFor()).isEqualTo("111");
		}
	}

	@Test
	public void test4() throws Exception {
		final Executor e = Executors.newSingleThreadExecutor();
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			ScriptRunner.Engine engine = runner.engine();
			final Lock<Object, Exception> lock0 = new Lock<>();
			final Lock<Boolean, Exception> endLock0 = new Lock<>();
			final Lock<Object, Exception> lock1 = new Lock<>();
			final Lock<Boolean, Exception> endLock1 = new Lock<>();
			
			engine.eval("var context;", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					lock1.fail(e);
				}
				@Override
				public void ended() {
				}
			});
			engine.register("echo", new AsyncScriptFunction<Object, Object>() {
				@Override
				public void call(final Object request, final Callback<Object> callback) {
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
			engine.eval("var f = function(a, c) { var _context = context; echo(a, function(aa) { _context.out(aa); c(); }); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					lock1.fail(e);
				}
				@Override
				public void ended() {
				}
			});
			
			ScriptRunner.Engine engine0 = engine.sub();
			engine0.register("out", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
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

			ScriptRunner.Engine engine1 = engine.sub();
			engine1.register("out", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
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
			Assertions.assertThat(lock0.waitFor()).isEqualTo("aaa0");
			Assertions.assertThat(lock1.waitFor()).isEqualTo("aaa1");
		}
	}
	
	@Test
	public void test5() throws Exception {
		final Executor e = Executors.newSingleThreadExecutor();
		try (ScriptRunner runner = new ExecutorScriptRunner()) {
			ScriptRunner.Engine engine = runner.engine();
			final Lock<Object, Exception> lock0 = new Lock<>();
			final Lock<Boolean, Exception> endLock0 = new Lock<>();
			final Lock<Object, Exception> lock1 = new Lock<>();
			final Lock<Boolean, Exception> endLock1 = new Lock<>();
			
			engine.eval("var context;", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					lock1.fail(e);
				}
				@Override
				public void ended() {
				}
			});
			engine.register("echo", new AsyncScriptFunction<Object, Object>() {
				@Override
				public void call(final Object request, final Callback<Object> callback) {
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
			engine.eval("var f = function(a, c) { var _context = context; echo(a, function(aa) { _context.out(aa); c(); }); };", new ScriptRunner.End() {
				@Override
				public void failed(Exception e) {
					lock0.fail(e);
					lock1.fail(e);
				}
				@Override
				public void ended() {
				}
			});
			
			ScriptRunner.Engine engine0 = engine.sub();
			engine0.register("out", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
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

			ScriptRunner.Engine engine1 = engine.sub();
			engine1.register("out", new SyncScriptFunction<Object, Object>() {
				@Override
				public Object call(Object request) {
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
			Assertions.assertThat(lock0.waitFor()).isEqualTo("aaa0");
			Assertions.assertThat(lock1.waitFor()).isEqualTo("aaa1");
		}
	}
}

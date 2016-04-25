package com.davfx.script;

import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.davfx.util.Lock;
import com.google.gson.JsonElement;

public class PerfTest {

	private static final Logger LOGGER = LoggerFactory.getLogger(PerfTest.class);
	
	private static void evalSync(String name) throws Exception {
		long min = Long.MAX_VALUE;
		for (int k = 0; k < 5; k++) {
			try (ScriptRunner runner = new ExecutorScriptRunner(name)) {
				long n = 1_000L;
				long t = System.currentTimeMillis();
				for (long i = 0; i < n; i++) {
					final Lock<Void, Exception> lock = new Lock<>();
					
					ScriptRunner.Engine engine = runner.engine();
					engine.register("syncEcho", new SyncScriptFunction() {
						@Override
						public JsonElement call(JsonElement request) {
							// LOGGER.debug("syncEcho({})", request);
							return request;
						}
					});
					engine.eval("var i" + i + " = 0; syncEcho({'message':'bb'});", new ScriptRunner.End() {
						@Override
						public void failed(Exception e) {
							lock.fail(e);
						}
						@Override
						public void ended() {
							// LOGGER.debug("end");
							lock.set(null);
						}
					});
					
					Assertions.assertThat(lock.waitFor()).isNull();
				}
				t = System.currentTimeMillis() - t;
				long tt = t * 1000L / n;
				min = Math.min(tt, min);
				LOGGER.debug("{}: {}", name, tt);
			}
		}
		LOGGER.debug("{}: min {}", name, min);
	}
	
	@Test
	public void testSync() throws Exception {
		evalSync("rhino");
		evalSync("nashorn");
		evalSync("jav8");
	}

}

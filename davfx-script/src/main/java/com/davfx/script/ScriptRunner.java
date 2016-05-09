package com.davfx.script;

public interface ScriptRunner extends AutoCloseable {
	interface End {
		void failed(Exception e);
		void ended();
	}
	
	/*%%
	<T, U> void register(String function, SyncScriptFunction<T, U> syncFunction);
	<T, U> void register(String function, AsyncScriptFunction<T, U> asyncFunction);

	void prepare(String script, End end);
	*/
	
	interface Engine {
		<T, U> void register(String function, SyncScriptFunction<T, U> syncFunction);
		<T, U> void register(String function, AsyncScriptFunction<T, U> asyncFunction);

		void eval(String script, End end);

		Engine sub();
	}
	Engine engine();
	
	void close();
}

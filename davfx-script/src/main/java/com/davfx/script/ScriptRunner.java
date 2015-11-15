package com.davfx.script;

public interface ScriptRunner extends AutoCloseable {
	interface End {
		void failed(Exception e);
		void ended();
	}
	
	void register(String function, SyncScriptFunction syncFunction);
	void register(String function, AsyncScriptFunction asyncFunction);

	void prepare(String script, End end);
	
	interface Engine {
		void register(String function, SyncScriptFunction syncFunction);
		void register(String function, AsyncScriptFunction asyncFunction);

		void eval(String script, End end);
	}
	Engine engine();
	
	void close();
}

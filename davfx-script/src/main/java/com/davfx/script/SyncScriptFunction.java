package com.davfx.script;

public interface SyncScriptFunction<T, U> {
	U call(T request);
}

package com.davfx.script;

import com.google.gson.JsonElement;

public interface AsyncScriptFunction {
	interface Callback { // extends Closeable {
		void handle(JsonElement response);
		void done();
	}
	void call(JsonElement request, Callback callback);
}

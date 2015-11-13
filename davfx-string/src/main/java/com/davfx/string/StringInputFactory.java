package com.davfx.string;

public interface StringInputFactory<T> {
	StringInput<T> build(StringInput<T>[] inputs);
}
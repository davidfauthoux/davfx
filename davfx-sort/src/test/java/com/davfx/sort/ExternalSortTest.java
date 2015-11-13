package com.davfx.sort;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.assertj.core.api.Assertions;
import org.junit.Test;

import com.google.common.base.Charsets;
import com.google.common.base.Function;

public class ExternalSortTest {

	@Test
	public void test() {
		ExternalSort<String> sort = ExternalSort.create(new Function<String, ByteBuffer>() {
			@Override
			public ByteBuffer apply(String input) {
				return ByteBuffer.wrap(input.getBytes(Charsets.UTF_8));
			}
		}, new Function<ByteBuffer, String>() {
			@Override
			public String apply(ByteBuffer input) {
				return new String(input.array(), input.position(), input.remaining(), Charsets.UTF_8);
			}
		});
		
		Random random = new Random(System.currentTimeMillis());
		List<String> l = new LinkedList<>();
		for (int i = 0; i < 500_000; i++) {
			l.add("test" + random.nextInt());
		}
		String previous = "";
		for (String s : sort.sorted(l)) {
			Assertions.assertThat(s.compareTo(previous)).isGreaterThanOrEqualTo(0);
			previous = s;
		}
	}

}

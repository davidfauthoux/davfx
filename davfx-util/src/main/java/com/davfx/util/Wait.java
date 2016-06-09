package com.davfx.util;

@Deprecated
public final class Wait implements Runnable {
	private boolean finished = false;

	public Wait() {
	}
	
	public synchronized void waitFor() {
		while (!finished) {
			try {
				wait();
			} catch (InterruptedException e) {
			}
		}
	}
	
	@Override
	public synchronized void run() {
		finished = true;
		notifyAll();
	}
}

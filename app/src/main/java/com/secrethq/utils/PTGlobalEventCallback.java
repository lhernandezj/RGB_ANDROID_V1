package com.secrethq.utils;

@FunctionalInterface
public interface PTGlobalEventCallback {
	void run(String name, String value);
}

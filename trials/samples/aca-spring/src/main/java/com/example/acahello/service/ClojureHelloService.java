package com.example.acahello.service;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ClojureHelloService {

    private static final IFn REQUIRE = Clojure.var("clojure.core", "require");
    private static final IFn HELLO_PAYLOAD;
    private static final IFn HELLO_NAME_PAYLOAD;
    private static final IFn STATUS_PAYLOAD;

    static {
        REQUIRE.invoke(Clojure.read("com.example.acahello.hello-service"));
        HELLO_PAYLOAD = Clojure.var("com.example.acahello.hello-service", "hello-payload");
        HELLO_NAME_PAYLOAD = Clojure.var("com.example.acahello.hello-service", "hello-name-payload");
        STATUS_PAYLOAD = Clojure.var("com.example.acahello.hello-service", "status-payload");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> hello() {
        return (Map<String, Object>) HELLO_PAYLOAD.invoke();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> hello(String name) {
        return (Map<String, Object>) HELLO_NAME_PAYLOAD.invoke(name);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> status() {
        return (Map<String, Object>) STATUS_PAYLOAD.invoke();
    }
}
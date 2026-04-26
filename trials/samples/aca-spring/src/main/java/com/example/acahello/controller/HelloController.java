package com.example.acahello.controller;

import com.example.acahello.service.ClojureHelloService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HelloController {

    private final ClojureHelloService clojureHelloService;

    public HelloController(ClojureHelloService clojureHelloService) {
        this.clojureHelloService = clojureHelloService;
    }

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        return clojureHelloService.hello();
    }

    @GetMapping("/hello/{name}")
    public Map<String, Object> helloName(@PathVariable String name) {
        return clojureHelloService.hello(name);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return clojureHelloService.status();
    }
}
package com.example;

public class FooController {
    private final BarService barService;

    public FooController(BarService barService) {
        this.barService = barService;
    }

    public String foo() {
        barService.bar();
        return "foo";
    }
}

package com.mowtiie.supanote.data.model;

public class Tag {

    private String id;
    private String name;
    private String color;

    public Tag() {
        // Default constructor
    }

    public Tag(String color, String name, String id) {
        this.color = color;
        this.name = name;
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }
}
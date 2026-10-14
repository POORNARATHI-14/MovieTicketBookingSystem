package com.movieticket.theatre;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "theatres")
public class Theatre {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 80)
    private String city;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(name = "total_screens", nullable = false)
    private int totalScreens;

    protected Theatre() {
    }

    public Theatre(String name, String city, String address, int totalScreens) {
        this.name = name;
        this.city = city;
        this.address = address;
        this.totalScreens = totalScreens;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }

    public String getAddress() {
        return address;
    }

    public int getTotalScreens() {
        return totalScreens;
    }
}

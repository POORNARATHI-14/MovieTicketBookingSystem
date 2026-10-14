package com.movieticket.movie;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "movies")
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 60)
    private String genre;

    @Column(name = "duration_min", nullable = false)
    private int durationMin;

    @Column(nullable = false, length = 40)
    private String language;

    @Column(length = 2000)
    private String description;

    protected Movie() {
    }

    public Movie(String title, String genre, int durationMin, String language, String description) {
        update(title, genre, durationMin, language, description);
    }

    public void update(String title, String genre, int durationMin, String language, String description) {
        this.title = title;
        this.genre = genre;
        this.durationMin = durationMin;
        this.language = language;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getGenre() {
        return genre;
    }

    public int getDurationMin() {
        return durationMin;
    }

    public String getLanguage() {
        return language;
    }

    public String getDescription() {
        return description;
    }
}

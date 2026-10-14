package com.movieticket.show;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A screening of a movie in one screen of a theatre. Other modules refer to it by id only. */
@Entity
@Table(name = "shows")
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "movie_id", nullable = false)
    private Long movieId;

    @Column(name = "theatre_id", nullable = false)
    private Long theatreId;

    @Column(name = "screen_no", nullable = false)
    private int screenNo;

    @Column(name = "show_time", nullable = false)
    private LocalDateTime showTime;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    protected Show() {
    }

    public Show(Long movieId, Long theatreId, int screenNo, LocalDateTime showTime, BigDecimal price) {
        this.movieId = movieId;
        this.theatreId = theatreId;
        this.screenNo = screenNo;
        this.showTime = showTime;
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public Long getMovieId() {
        return movieId;
    }

    public Long getTheatreId() {
        return theatreId;
    }

    public int getScreenNo() {
        return screenNo;
    }

    public LocalDateTime getShowTime() {
        return showTime;
    }

    public BigDecimal getPrice() {
        return price;
    }
}

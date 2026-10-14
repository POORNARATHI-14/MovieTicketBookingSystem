package com.movieticket.show;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShowRepository extends JpaRepository<Show, Long> {

    boolean existsByMovieId(Long movieId);

    boolean existsByTheatreIdAndScreenNoAndShowTime(Long theatreId, int screenNo, LocalDateTime showTime);

    @Query("""
            select s from Show s
            where (:movieId is null or s.movieId = :movieId)
              and (:theatreId is null or s.theatreId = :theatreId)
              and (:fromTime is null or s.showTime >= :fromTime)
              and (:toTime is null or s.showTime < :toTime)
            order by s.showTime
            """)
    List<Show> search(@Param("movieId") Long movieId, @Param("theatreId") Long theatreId,
                      @Param("fromTime") LocalDateTime fromTime, @Param("toTime") LocalDateTime toTime);
}

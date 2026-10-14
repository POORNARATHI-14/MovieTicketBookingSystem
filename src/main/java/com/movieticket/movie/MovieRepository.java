package com.movieticket.movie;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    @Query("""
            select m from Movie m
            where (:genre is null or lower(m.genre) = lower(:genre))
              and (:language is null or lower(m.language) = lower(:language))
              and (:title is null or lower(m.title) like lower(concat('%', :title, '%')))
            order by m.title
            """)
    List<Movie> search(@Param("genre") String genre, @Param("language") String language, @Param("title") String title);
}

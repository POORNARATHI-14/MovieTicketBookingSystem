package com.movieticket.theatre;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TheatreRepository extends JpaRepository<Theatre, Long> {

    List<Theatre> findByCityIgnoreCaseOrderByName(String city);
}

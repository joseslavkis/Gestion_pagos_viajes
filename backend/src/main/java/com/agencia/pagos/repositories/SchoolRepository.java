package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.School;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchoolRepository extends JpaRepository<School, Long> {

    Optional<School> findByNormalizedName(String normalizedName);

    List<School> findAllByOrderByNameAsc();
}

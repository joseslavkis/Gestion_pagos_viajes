package com.agencia.pagos.user;

import com.agencia.pagos.user.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByDni(String dni);

    List<Student> findByParentId(Long parentId);

    boolean existsByDni(String dni);

    List<Student> findByDniIn(Collection<String> dnis);
}

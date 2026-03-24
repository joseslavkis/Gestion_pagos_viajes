package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.SchoolCreateDTO;
import com.agencia.pagos.dtos.response.SchoolDTO;
import com.agencia.pagos.entities.School;
import com.agencia.pagos.repositories.SchoolRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class SchoolService {

    private final SchoolRepository schoolRepository;

    @Autowired
    public SchoolService(SchoolRepository schoolRepository) {
        this.schoolRepository = schoolRepository;
    }

    @Transactional(readOnly = true)
    public List<SchoolDTO> getAll() {
        return schoolRepository.findAllByOrderByNameAsc().stream()
                .map(this::toDTO)
                .toList();
    }

    public SchoolDTO create(SchoolCreateDTO dto) {
        String cleanedName = cleanName(dto.name());
        String normalizedName = normalizeName(cleanedName);

        schoolRepository.findByNormalizedName(normalizedName)
                .ifPresent(existing -> {
                    throw new IllegalStateException("Ya existe un colegio con ese nombre.");
                });

        School school = School.builder()
                .name(cleanedName)
                .normalizedName(normalizedName)
                .build();

        return toDTO(schoolRepository.save(school));
    }

    private SchoolDTO toDTO(School school) {
        return new SchoolDTO(school.getId(), school.getName());
    }

    private String cleanName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ");
    }

    private String normalizeName(String value) {
        String withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return withoutAccents
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }
}

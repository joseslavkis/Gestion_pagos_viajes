package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.NotEmpty;
import org.hibernate.validator.constraints.UniqueElements;
import java.util.List;

public record UserAssignBulkDTO(
        @NotEmpty @UniqueElements List<Long> userIds
) {}

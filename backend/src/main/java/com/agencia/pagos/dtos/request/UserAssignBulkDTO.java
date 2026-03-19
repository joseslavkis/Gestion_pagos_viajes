package com.agencia.pagos.dtos.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;
import java.util.List;

public record UserAssignBulkDTO(
        @NotEmpty @UniqueElements @Size(max = 500, message = "Cannot assign more than 500 users at once") List<Long> userIds
) {}


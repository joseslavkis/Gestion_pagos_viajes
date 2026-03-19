import { useMutation } from "@tanstack/react-query";

import { ApiError } from "@/lib/api-error";
import { apiPost } from "@/lib/api-client";
import {
  ContactMessageDTO,
  ContactSendResponse,
  ContactSendResponseSchema,
} from "@/features/contact/types/contact-dtos";

export function useSendContactMessage() {
  return useMutation<ContactSendResponse, ApiError, ContactMessageDTO>({
    mutationFn: async (dto) => {
      return apiPost("/api/v1/contact/send", dto, (json) => ContactSendResponseSchema.parse(json));
    },
  });
}


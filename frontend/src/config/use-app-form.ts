import { createFormHook } from "@tanstack/react-form";

import { FormContainer } from "@/components/form-components/FormContainer/FormContainer";
import { NumberField, PasswordField, TextField } from "@/components/form-components/InputFields/InputFields";
import { SubmitButton } from "@/components/form-components/SubmitButton/SubmitButton";
import { fieldContext, formContext } from "@/config/form-context";

export const { useAppForm } = createFormHook({
  fieldContext,
  formContext,
  fieldComponents: {
    TextField,
    NumberField,
    PasswordField,
  },
  formComponents: {
    FormContainer,
    SubmitButton,
  },
});

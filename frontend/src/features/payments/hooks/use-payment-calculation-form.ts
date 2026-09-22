import { useEffect, useMemo, useReducer } from "react";

import { usePaymentCalculation } from "@/features/payments/services/payments-service";
import type {
  Currency,
  PaymentCalculationRequestDTO,
  PaymentCalculationResponseDTO,
} from "@/features/payments/types/payments-dtos";
import { normalizePaymentDecimalInput } from "@/features/payments/types/decimal-strings";

export type PaymentCalculationFormContext = {
  anchorInstallmentId: number;
  tripCurrency: Currency;
  remainingAmount: string;
  reportedPaymentDate: string;
};

type SourceIntent = {
  kind: "REMAINING" | "MANUAL";
  currency: Currency;
  amount: string;
  revision: number;
};

type FormState = {
  context: PaymentCalculationFormContext | null;
  paymentCurrency: Currency;
  amountInput: string;
  sourceIntent: SourceIntent | null;
  calculation: PaymentCalculationResponseDTO | null;
};

type FormAction =
  | { type: "SYNC_CONTEXT"; context: PaymentCalculationFormContext | null }
  | { type: "CHANGE_DATE"; reportedPaymentDate: string }
  | { type: "CHANGE_CURRENCY"; paymentCurrency: Currency }
  | { type: "EDIT_AMOUNT"; amountInput: string }
  | {
      type: "ACCEPT_CALCULATION";
      requestKey: string;
      calculation: PaymentCalculationResponseDTO;
    };

const initialState: FormState = {
  context: null,
  paymentCurrency: "ARS",
  amountInput: "",
  sourceIntent: null,
  calculation: null,
};

function nextRevision(state: FormState): number {
  return (state.sourceIntent?.revision ?? 0) + 1;
}

function createRemainingState(
  state: FormState,
  context: PaymentCalculationFormContext,
): FormState {
  return {
    context,
    paymentCurrency: context.tripCurrency,
    amountInput: context.remainingAmount,
    sourceIntent: {
      kind: "REMAINING",
      currency: context.tripCurrency,
      amount: context.remainingAmount,
      revision: nextRevision(state),
    },
    calculation: null,
  };
}

function requestKey(state: FormState): string | null {
  if (state.context == null || state.sourceIntent == null) {
    return null;
  }

  return [
    state.context.anchorInstallmentId,
    state.context.reportedPaymentDate,
    state.paymentCurrency,
    state.sourceIntent.currency,
    state.sourceIntent.amount,
    state.sourceIntent.kind,
    state.sourceIntent.revision,
  ].join("|");
}

function formReducer(state: FormState, action: FormAction): FormState {
  switch (action.type) {
    case "SYNC_CONTEXT": {
      if (action.context == null) {
        return state.context == null ? state : initialState;
      }

      if (
        state.context == null ||
        state.context.anchorInstallmentId !== action.context.anchorInstallmentId ||
        state.context.tripCurrency !== action.context.tripCurrency
      ) {
        return createRemainingState(state, action.context);
      }

      const dateChanged = state.context.reportedPaymentDate !== action.context.reportedPaymentDate;
      const remainingChanged = state.context.remainingAmount !== action.context.remainingAmount;
      if (!dateChanged && !remainingChanged) {
        return state;
      }

      if (state.sourceIntent?.kind === "MANUAL") {
        return {
          ...state,
          context: action.context,
          sourceIntent: {
            ...state.sourceIntent,
            revision: nextRevision(state),
          },
          calculation: null,
        };
      }

      return {
        ...state,
        context: action.context,
        amountInput:
          state.paymentCurrency === action.context.tripCurrency
            ? action.context.remainingAmount
            : state.amountInput,
        sourceIntent: {
          kind: "REMAINING",
          currency: action.context.tripCurrency,
          amount: action.context.remainingAmount,
          revision: nextRevision(state),
        },
        calculation: null,
      };
    }

    case "CHANGE_DATE": {
      if (state.context == null || state.sourceIntent == null) {
        return state;
      }
      if (state.context.reportedPaymentDate === action.reportedPaymentDate) {
        return state;
      }
      return {
        ...state,
        context: {
          ...state.context,
          reportedPaymentDate: action.reportedPaymentDate,
        },
        sourceIntent: {
          ...state.sourceIntent,
          revision: nextRevision(state),
        },
        calculation: null,
      };
    }

    case "CHANGE_CURRENCY": {
      if (state.sourceIntent == null || state.paymentCurrency === action.paymentCurrency) {
        return state;
      }
      return {
        ...state,
        paymentCurrency: action.paymentCurrency,
        amountInput:
          action.paymentCurrency === state.sourceIntent.currency
            ? state.sourceIntent.amount
            : state.amountInput,
        sourceIntent: {
          ...state.sourceIntent,
          revision: nextRevision(state),
        },
        calculation: null,
      };
    }

    case "EDIT_AMOUNT": {
      if (state.context == null) {
        return state;
      }
      return {
        ...state,
        amountInput: action.amountInput,
        sourceIntent: {
          kind: "MANUAL",
          currency: state.paymentCurrency,
          amount: action.amountInput,
          revision: nextRevision(state),
        },
        calculation: null,
      };
    }

    case "ACCEPT_CALCULATION": {
      if (action.requestKey !== requestKey(state) || state.context == null || state.sourceIntent == null) {
        return state;
      }
      if (
        action.calculation.anchorInstallmentId !== state.context.anchorInstallmentId ||
        action.calculation.reportedPaymentDate !== state.context.reportedPaymentDate ||
        action.calculation.paymentCurrency !== state.paymentCurrency ||
        action.calculation.intent !== state.sourceIntent.kind
      ) {
        return state;
      }

      return {
        ...state,
        amountInput:
          state.sourceIntent.kind === "REMAINING" && action.calculation.reportedAmount != null
            ? action.calculation.reportedAmount
            : state.amountInput,
        calculation: action.calculation,
      };
    }
  }
}

function buildPayload(state: FormState): PaymentCalculationRequestDTO | null {
  if (state.context == null || state.sourceIntent == null) {
    return null;
  }

  const base = {
    anchorInstallmentId: state.context.anchorInstallmentId,
    paymentCurrency: state.paymentCurrency,
    reportedPaymentDate: state.context.reportedPaymentDate,
  };

  if (state.sourceIntent.kind === "REMAINING") {
    return {
      ...base,
      intent: "REMAINING",
    };
  }

  if (state.sourceIntent.currency !== state.paymentCurrency) {
    return null;
  }

  const reportedAmount = normalizePaymentDecimalInput(state.sourceIntent.amount);
  if (reportedAmount == null || !/[1-9]/.test(reportedAmount)) {
    return null;
  }

  return {
    ...base,
    intent: "MANUAL",
    reportedAmount,
  };
}

export function usePaymentCalculationForm(context: PaymentCalculationFormContext | null) {
  const [state, dispatch] = useReducer(formReducer, initialState);

  useEffect(() => {
    dispatch({ type: "SYNC_CONTEXT", context });
  }, [context]);

  const payload = useMemo(() => buildPayload(state), [state]);
  const activeRequestKey = requestKey(state);
  const amountCurrencyMismatch =
    state.sourceIntent?.kind === "MANUAL" && state.sourceIntent.currency !== state.paymentCurrency
      ? {
          sourceCurrency: state.sourceIntent.currency,
          paymentCurrency: state.paymentCurrency,
        }
      : null;
  const query = usePaymentCalculation(
    payload,
    state.sourceIntent == null
      ? undefined
      : {
          sourceCurrency: state.sourceIntent.currency,
          sourceAmount: state.sourceIntent.amount,
          intentRevision: state.sourceIntent.revision,
        },
  );

  useEffect(() => {
    if (query.data == null || activeRequestKey == null) {
      return;
    }
    dispatch({
      type: "ACCEPT_CALCULATION",
      requestKey: activeRequestKey,
      calculation: query.data,
    });
  }, [activeRequestKey, query.data]);

  return {
    amountInput: state.amountInput,
    paymentCurrency: state.paymentCurrency,
    calculation: state.calculation,
    calculationError: query.error,
    amountCurrencyMismatch,
    isCalculating: payload != null && query.isFetching,
    setAmountInput: (amountInput: string) => dispatch({ type: "EDIT_AMOUNT", amountInput }),
    setPaymentCurrency: (paymentCurrency: Currency) =>
      dispatch({ type: "CHANGE_CURRENCY", paymentCurrency }),
    setReportedPaymentDate: (reportedPaymentDate: string) =>
      dispatch({ type: "CHANGE_DATE", reportedPaymentDate }),
  };
}

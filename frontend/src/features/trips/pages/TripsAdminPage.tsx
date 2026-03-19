import { useEffect, useMemo, useRef, useState } from "react";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useLocation } from "wouter";
import { useAppForm } from "@/config/use-app-form";
import {
  TripCreateDTOSchema,
  TripUpdateDTOSchema,
  type TripSummaryDTO,
  type TripUpdateDTO,
  UserAssignBulkDTOSchema,
} from "@/features/trips/types/trips-dtos";
import {
  useAssignUsersBulk,
  useCreateTrip,
  useDeleteTrip,
  useTrip,
  useTrips,
  useUpdateTrip,
} from "@/features/trips/services/trips-service";

import styles from "./TripsAdminPage.module.css";

const currencyFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "ARS",
});

type ModalState =
  | { type: "none" }
  | { type: "create" }
  | { type: "edit"; tripId: number }
  | { type: "assign"; tripId: number; tripName: string }
  | { type: "delete"; trip: TripSummaryDTO };

export function TripsAdminPage() {
  const { data, isLoading, error, refetch } = useTrips();
  const [modalState, setModalState] = useState<ModalState>({ type: "none" });

  const openCreate = () => setModalState({ type: "create" });
  const closeModal = () => setModalState({ type: "none" });

  const handleEdit = (tripId: number) => setModalState({ type: "edit", tripId });
  const handleAssign = (trip: TripSummaryDTO) =>
    setModalState({ type: "assign", tripId: trip.id, tripName: trip.name });
  const handleDelete = (trip: TripSummaryDTO) => setModalState({ type: "delete", trip });

  const trips = data ?? [];

  const totalTrips = trips.length;

  return (
    <CommonLayout>
      <section className={styles.page}>
        <header className={styles.header}>
          <div className={styles.titleRow}>
            <div className={styles.titleBlock}>
              <h1 className={styles.title}>Viajes</h1>
              <p className={styles.subtitle}>Configura montos, cuotas y asignaciones para tus viajes escolares.</p>
              <span className={styles.countBadge}>
                <span>Viajes:</span> <span>{totalTrips}</span>
              </span>
            </div>
            <button type="button" className={styles.newTripButton} onClick={openCreate}>
              <span className={styles.newTripButtonIcon}>＋</span>
              <span>Nuevo viaje</span>
            </button>
          </div>
        </header>

        <RequestState isLoading={isLoading} error={error ?? null} loadingLabel="Cargando viajes...">
          {isLoading ? (
            <div className={styles.grid} aria-hidden="true">
              {Array.from({ length: 3 }).map((_, index) => (
                <article key={index} className={`${styles.cardSkeleton} ${styles.cardConfiguring}`}>
                  <div className={styles.cardHeader}>
                    <div className={styles.cardTitleBlock}>
                      <div className={`${styles.skeletonLine} ${styles.skeletonShort}`} />
                      <div className={`${styles.skeletonLine} ${styles.skeletonMeta}`} />
                    </div>
                  </div>
                  <div className={styles.cardBody}>
                    <div className={styles.skeletonRow}>
                      <div className={styles.skeletonLine} />
                      <div className={styles.skeletonLine} />
                    </div>
                    <div className={styles.skeletonRow}>
                      <div className={styles.skeletonLine} />
                      <div className={styles.skeletonLine} />
                    </div>
                  </div>
                  <div className={styles.cardFooter}>
                    <div className={`${styles.skeletonLine} ${styles.skeletonShort}`} />
                  </div>
                </article>
              ))}
            </div>
          ) : trips.length === 0 ? (
            <div className={styles.emptyState}>
              <h2 className={styles.emptyTitle}>Todavía no tienes viajes configurados</h2>
              <p className={styles.emptyDescription}>
                Crea tu primer viaje para comenzar a asignar familias y organizar los pagos de manera ordenada.
              </p>
              <button type="button" className={styles.newTripButton} onClick={openCreate}>
                <span className={styles.newTripButtonIcon}>＋</span>
                <span>Crear primer viaje</span>
              </button>
            </div>
          ) : (
            <div className={styles.grid}>
              {trips.map((trip) => (
                <TripCard
                  key={trip.id}
                  trip={trip}
                  onEdit={handleEdit}
                  onAssign={handleAssign}
                  onDelete={handleDelete}
                />
              ))}
            </div>
          )}
        </RequestState>

        {modalState.type === "create" ? (
          <TripModalCreate onClose={closeModal} />
        ) : modalState.type === "edit" ? (
          <TripModalEdit tripId={modalState.tripId} onClose={closeModal} />
        ) : modalState.type === "assign" ? (
          <AssignUsersModal
            tripId={modalState.tripId}
            tripName={modalState.tripName}
            onClose={closeModal}
          />
        ) : modalState.type === "delete" ? (
          <DeleteTripModal trip={modalState.trip} onClose={closeModal} />
        ) : null}

        {error ? (
          <button type="button" className={styles.newTripButton} onClick={() => refetch()}>
            Reintentar carga
          </button>
        ) : null}
      </section>
    </CommonLayout>
  );
}

type TripCardProps = {
  trip: TripSummaryDTO;
  onEdit: (tripId: number) => void;
  onAssign: (trip: TripSummaryDTO) => void;
  onDelete: (trip: TripSummaryDTO) => void;
};

function TripCard({ trip, onEdit, onAssign, onDelete }: TripCardProps) {
  const [, setLocation] = useLocation();
  const status = trip.assignedUsersCount > 0 ? "active" : "configuring";
  const isActive = status === "active";

  return (
    <article
      className={`${styles.card} ${isActive ? styles.cardActive : styles.cardConfiguring}`}
      aria-label={`Viaje ${trip.name}`}
    >
      <div className={styles.cardHeader}>
        <div className={styles.cardTitleBlock}>
          <button
            type="button"
            className={styles.cardTitle}
            onClick={() => {
              if (trip.assignedUsersCount > 0) {
                setLocation(`/trips/${trip.id}/spreadsheet`);
              }
            }}
          >
            {trip.name}
          </button>
          <p className={styles.cardMeta}>
            {trip.installmentsCount} cuotas · {trip.assignedUsersCount} participantes
          </p>
        </div>
        <span
          className={`${styles.statusPill} ${
            isActive ? styles.statusActive : styles.statusConfiguring
          }`}
        >
          {isActive ? "Activo" : "Configurando"}
        </span>
      </div>
      <div className={styles.cardBody}>
        <span className={styles.label}>Monto total</span>
        <span className={styles.value}>{currencyFormatter.format(trip.totalAmount)}</span>
        <span className={styles.label}>Usuarios asignados</span>
        <span className={styles.value}>{trip.assignedUsersCount}</span>
      </div>
      <div className={styles.cardFooter}>
        <button
          type="button"
          className={styles.chipButton}
          onClick={() => onEdit(trip.id)}
          aria-label={`Editar viaje ${trip.name}`}
        >
          ✏️ Editar
        </button>
        <button
          type="button"
          className={styles.chipButton}
          onClick={() => onAssign(trip)}
          aria-label={`Asignar usuarios al viaje ${trip.name}`}
        >
          👥 Asignar usuarios
        </button>
        <TooltipWrapper
          disabled={trip.assignedUsersCount > 0}
          message="Asigna usuarios primero para ver la plantilla"
        >
          <button
            type="button"
            className={`${styles.chipButton} ${
              trip.assignedUsersCount === 0 ? styles.chipDisabled : ""
            }`}
            onClick={() => {
              if (trip.assignedUsersCount > 0) {
                setLocation(`/trips/${trip.id}/spreadsheet`);
              }
            }}
            aria-label={
              trip.assignedUsersCount > 0
                ? `Ver plantilla del viaje ${trip.name}`
                : `No se puede ver la plantilla de ${trip.name} porque aún no tiene usuarios asignados`
            }
            disabled={trip.assignedUsersCount === 0}
          >
            📊 Ver plantilla
          </button>
        </TooltipWrapper>
        <TooltipWrapper
          disabled={trip.assignedUsersCount === 0}
          message="No es posible eliminar un viaje con usuarios asignados."
        >
          <button
            type="button"
            className={`${styles.chipButton} ${styles.chipDanger} ${
              trip.assignedUsersCount > 0 ? styles.chipDisabled : ""
            }`}
            onClick={() => {
              if (trip.assignedUsersCount === 0) {
                onDelete(trip);
              }
            }}
            aria-label={
              trip.assignedUsersCount === 0
                ? `Eliminar viaje ${trip.name}`
                : `No se puede eliminar viaje ${trip.name} porque tiene usuarios asignados`
            }
            disabled={trip.assignedUsersCount > 0}
          >
            🗑 Eliminar
          </button>
        </TooltipWrapper>
      </div>
    </article>
  );
}

type ModalProps = {
  title: string;
  description?: string;
  onClose: () => void;
  children: React.ReactNode;
};

function ModalShell({ title, description, onClose, children }: ModalProps) {
  const overlayRef = useRef<HTMLDivElement | null>(null);
  const dialogRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      }
      if (event.key === "Tab" && dialogRef.current) {
        const focusableSelectors =
          'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';
        const focusable = dialogRef.current.querySelectorAll<HTMLElement>(focusableSelectors);
        const focusableArray = Array.from(focusable);
        if (focusableArray.length === 0) {
          return;
        }

        const first = focusableArray[0];
        const last = focusableArray[focusableArray.length - 1];

        if (event.shiftKey) {
          if (document.activeElement === first) {
            event.preventDefault();
            last.focus();
          }
        } else if (document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    const focusTarget = dialogRef.current?.querySelector<HTMLElement>("h2, button, input, textarea");
    focusTarget?.focus();
  }, []);

  return (
    <div
      ref={overlayRef}
      className={styles.overlay}
      role="presentation"
      onClick={(event) => {
        if (event.target === overlayRef.current) {
          onClose();
        }
      }}
    >
      <div
        ref={dialogRef}
        className={styles.modal}
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialog-title"
      >
        <header className={styles.modalHeader}>
          <div>
            <h2 id="dialog-title" className={styles.modalTitle}>
              {title}
            </h2>
            {description ? <p className={styles.modalDescription}>{description}</p> : null}
          </div>
          <button
            type="button"
            className={styles.modalCloseButton}
            onClick={onClose}
            aria-label="Cerrar diálogo"
          >
            Cerrar
          </button>
        </header>
        <div className={styles.modalBody}>{children}</div>
      </div>
    </div>
  );
}

type TripModalCreateProps = {
  onClose: () => void;
};

function TripModalCreate({ onClose }: TripModalCreateProps) {
  const { mutateAsync, error, isPending } = useCreateTrip();

  const formData = useAppForm({
    defaultValues: {
      name: "",
      totalAmount: 0,
      installmentsCount: 1,
      dueDay: 1,
      yellowWarningDays: 0,
      fixedFineAmount: 0,
      retroactiveActive: false,
      firstDueDate: "",
    },
    validators: {
      onChange: TripCreateDTOSchema,
    },
    onSubmit: async ({ value }) => {
      await mutateAsync(value);
      onClose();
    },
  });

  return (
    <ModalShell
      title="Nuevo viaje"
      description="Define el monto total, cuotas y parámetros de vencimiento. Podrás asignar usuarios luego."
      onClose={onClose}
    >
      <RequestState isLoading={isPending} error={error ?? null} loadingLabel="Creando viaje...">
        <formData.AppForm>
          <formData.FormContainer submitLabel="Crear viaje" pendingLabel="Creando viaje..." isPending={isPending}>
            <div className={styles.fieldGroup}>
              <formData.AppField
                name="name"
                children={(field) => (
                  <field.TextField
                    label="Nombre del viaje"
                    placeholder="Ej: Bariloche 5to año 2025"
                    autoComplete="off"
                  />
                )}
              />

              <div className={styles.inlineFields}>
                <formData.AppField
                  name="totalAmount"
                  children={(field) => (
                    <field.NumberField
                      label="Monto total (ARS)"
                      placeholder="Ej: 1500000"
                      autoComplete="off"
                      min={0}
                      step={0.01}
                    />
                  )}
                />
                <formData.AppField
                  name="installmentsCount"
                  children={(field) => (
                    <field.NumberField
                      label="Cantidad de cuotas"
                      placeholder="Ej: 12"
                      autoComplete="off"
                      min={1}
                      max={60}
                      step={1}
                    />
                  )}
                />
              </div>

              <div className={styles.inlineFields}>
                <formData.AppField
                  name="dueDay"
                  children={(field) => (
                    <field.NumberField
                      label="Día de vencimiento"
                      placeholder="1-31"
                      autoComplete="off"
                      min={1}
                      max={31}
                      step={1}
                    />
                  )}
                />
                <formData.AppField
                  name="yellowWarningDays"
                  children={(field) => (
                    <field.NumberField
                      label="Días de aviso amarillo"
                      placeholder="0-30"
                      autoComplete="off"
                      min={0}
                      max={30}
                      step={1}
                    />
                  )}
                />
              </div>

              <formData.AppField
                name="fixedFineAmount"
                children={(field) => (
                  <field.NumberField
                    label="Recargo fijo por mora"
                    placeholder="Ej: 2000"
                    autoComplete="off"
                    min={0}
                    step={0.01}
                  />
                )}
              />

              <formData.AppField
                name="firstDueDate"
                children={(field) => (
                  <field.TextField
                    label="Primera fecha de vencimiento"
                    placeholder="YYYY-MM-DD"
                    autoComplete="off"
                  />
                )}
              />

              <formData.AppField
                name="retroactiveActive"
                children={(field) => (
                  <div className={styles.checkboxRow}>
                    <input
                      id="retroactiveActive"
                      type="checkbox"
                      name={field.name}
                      checked={field.state.value}
                      onChange={(event) => field.handleChange(event.target.checked)}
                    />
                    <label htmlFor="retroactiveActive">
                      Activar retroactivo en recargos
                      <span className={styles.checkboxHint}>
                        {" "}
                        Aplica la lógica de multas a cuotas ya vencidas.
                      </span>
                    </label>
                  </div>
                )}
              />

              {error?.fieldErrors?.length ? (
                <ul className={styles.fieldErrorList}>
                  {error.fieldErrors.map((message) => (
                    <li key={message}>{message}</li>
                  ))}
                </ul>
              ) : null}
            </div>
          </formData.FormContainer>
        </formData.AppForm>
      </RequestState>
    </ModalShell>
  );
}

type TripModalEditProps = {
  tripId: number;
  onClose: () => void;
};

function TripModalEdit({ tripId, onClose }: TripModalEditProps) {
  const { data, isLoading, error: loadError } = useTrip(tripId);
  const { mutateAsync, error: mutateError, isPending } = useUpdateTrip();

  const trip = data;

  const isLoadingState = isLoading || !trip;
  const combinedError = loadError ?? mutateError ?? null;

  const formData = useAppForm({
    defaultValues: (trip
      ? {
          name: trip.name,
          dueDay: trip.dueDay,
          yellowWarningDays: trip.yellowWarningDays,
          fixedFineAmount: trip.fixedFineAmount,
          retroactiveActive: trip.retroactiveActive,
          firstDueDate: trip.firstDueDate,
        }
      : {
          name: "",
          dueDay: 1,
          yellowWarningDays: 0,
          fixedFineAmount: 0,
          retroactiveActive: false,
          firstDueDate: "",
        }) as TripUpdateDTO,
    validators: {
      onChange: TripUpdateDTOSchema,
    },
    onSubmit: async ({ value }) => {
      if (!trip) {
        return;
      }
      await mutateAsync({ id: trip.id, data: value });
      onClose();
    },
  });

  const hasAssignedUsers = trip?.assignedUsersCount ? trip.assignedUsersCount > 0 : false;

  return (
    <ModalShell
      title={trip ? `Editar viaje: ${trip.name}` : "Editar viaje"}
      description="Actualiza los parámetros de vencimiento y recargos. Algunos campos se bloquean si el viaje ya tiene usuarios."
      onClose={onClose}
    >
      <RequestState
        isLoading={isLoadingState || isPending}
        error={combinedError}
        loadingLabel={isLoadingState ? "Cargando datos del viaje..." : "Guardando cambios..."}
      >
        {isLoadingState ? (
          <div className={styles.fieldGroup}>
            <div className={styles.skeletonLine} />
            <div className={styles.inlineFields}>
              <div className={styles.skeletonLine} />
              <div className={styles.skeletonLine} />
            </div>
            <div className={styles.skeletonLine} />
          </div>
        ) : trip ? (
          <formData.AppForm>
            <formData.FormContainer
              submitLabel="Guardar cambios"
              pendingLabel="Guardando..."
              isPending={isPending}
              extraError={combinedError}
            >
              <div className={styles.fieldGroup}>
                <formData.AppField
                  name="name"
                  children={(field) => (
                    <field.TextField label="Nombre del viaje" placeholder="Ej: Bariloche 5to año 2025" />
                  )}
                />

                <div className={styles.inlineFields}>
                  <formData.AppField
                    name="dueDay"
                    children={(field) => (
                      <field.NumberField
                        label="Día de vencimiento"
                        placeholder="1-31"
                        autoComplete="off"
                        min={1}
                        max={31}
                        step={1}
                      />
                    )}
                  />
                  <formData.AppField
                    name="yellowWarningDays"
                    children={(field) => (
                      <field.NumberField
                        label="Días de aviso amarillo"
                        placeholder="0-30"
                        autoComplete="off"
                        min={0}
                        max={30}
                        step={1}
                      />
                    )}
                  />
                </div>

                <formData.AppField
                  name="fixedFineAmount"
                  children={(field) => (
                    <field.NumberField
                      label="Recargo fijo por mora"
                      placeholder="Ej: 2000"
                      autoComplete="off"
                      min={0}
                      step={0.01}
                    />
                  )}
                />

                <TooltipWrapper
                  disabled={!hasAssignedUsers}
                  message="No es posible modificar la primera fecha de vencimiento cuando el viaje ya tiene usuarios asignados."
                >
                  <formData.AppField
                    name="firstDueDate"
                    children={(field) => (
                      <field.TextField
                        label="Primera fecha de vencimiento"
                        placeholder="YYYY-MM-DD"
                        autoComplete="off"
                      />
                    )}
                  />
                </TooltipWrapper>

                <formData.AppField
                  name="retroactiveActive"
                  children={(field) => (
                    <div className={styles.checkboxRow}>
                      <input
                        id="retroactiveActive-edit"
                        type="checkbox"
                        name={field.name}
                        checked={field.state.value}
                        onChange={(event) => field.handleChange(event.target.checked)}
                      />
                      <label htmlFor="retroactiveActive-edit">
                        Activar retroactivo en recargos
                        <span className={styles.checkboxHint}>
                          {" "}
                          Aplica la lógica de multas a cuotas ya vencidas.
                        </span>
                      </label>
                    </div>
                  )}
                />

                {mutateError?.fieldErrors?.length ? (
                  <ul className={styles.fieldErrorList}>
                    {mutateError.fieldErrors.map((message) => (
                      <li key={message}>{message}</li>
                    ))}
                  </ul>
                ) : null}
              </div>
            </formData.FormContainer>
          </formData.AppForm>
        ) : (
          <div className={styles.fieldGroup}>
            <p className={styles.modalDescription}>No pudimos cargar los datos del viaje.</p>
          </div>
        )}
      </RequestState>
    </ModalShell>
  );
}

type AssignUsersModalProps = {
  tripId: number;
  tripName: string;
  onClose: () => void;
};

function AssignUsersModal({ tripId, tripName, onClose }: AssignUsersModalProps) {
  const { mutateAsync, error, isPending, data } = useAssignUsersBulk();
  const [rawInput, setRawInput] = useState("");

  const parsedIds = useMemo(() => {
    const parts = rawInput
      .split(/[\s,]+/)
      .map((part) => part.trim())
      .filter((part) => part.length > 0);
    const numbers = parts.map((part) => Number(part)).filter((value) => Number.isInteger(value) && value >= 0);
    const unique = Array.from(new Set(numbers));
    return unique.slice(0, 500);
  }, [rawInput]);

  const parsedDtoResult = useMemo(() => {
    try {
      return UserAssignBulkDTOSchema.parse({ userIds: parsedIds });
    } catch {
      return null;
    }
  }, [parsedIds]);

  const hasValidIds = parsedDtoResult !== null && parsedDtoResult.userIds.length > 0;

  const handleSubmit = async () => {
    if (!parsedDtoResult) {
      return;
    }
    await mutateAsync({ id: tripId, data: parsedDtoResult });
  };

  useEffect(() => {
    if (data && !isPending) {
      onClose();
    }
  }, [data, isPending, onClose]);

  return (
    <ModalShell
      title={`Asignar usuarios a ${tripName}`}
      description="Pega o escribe los IDs de usuario separados por coma o salto de línea. Máximo 500 IDs, sin duplicados."
      onClose={onClose}
    >
      <RequestState
        isLoading={isPending}
        error={error ?? null}
        loadingLabel="Asignando usuarios al viaje..."
      >
        <>
          <label className={styles.fieldGroup}>
            <span className={styles.label}>IDs de usuario</span>
            <textarea
              className={styles.idsTextArea}
              value={rawInput}
              onChange={(event) => setRawInput(event.target.value)}
              placeholder="Ej: 101, 102, 103 o uno por línea"
            />
            <p className={styles.idsHelper}>
              Se aceptan solo números. Los duplicados se eliminarán automáticamente. Máximo 500 IDs.
            </p>
            <p className={styles.idsPreview}>
              IDs válidos detectados: {parsedIds.length}
              {parsedIds.length > 0 ? ` · Se enviarán ${Math.min(parsedIds.length, 500)} IDs` : ""}
            </p>
          </label>
          <div className={styles.modalFooter}>
            <button
              type="button"
              className={styles.newTripButton}
              onClick={handleSubmit}
              disabled={!hasValidIds || isPending}
            >
              Confirmar asignación
            </button>
          </div>
        </>
      </RequestState>
    </ModalShell>
  );
}

type DeleteTripModalProps = {
  trip: TripSummaryDTO;
  onClose: () => void;
};

function DeleteTripModal({ trip, onClose }: DeleteTripModalProps) {
  const { mutateAsync, error, isPending } = useDeleteTrip();

  const handleConfirm = async () => {
    await mutateAsync({ id: trip.id });
    onClose();
  };

  return (
    <ModalShell
      title="Eliminar viaje"
      description="Esta acción es permanente. Solo puedes eliminar viajes sin usuarios asignados."
      onClose={onClose}
    >
      <RequestState
        isLoading={isPending}
        error={error ?? null}
        loadingLabel="Eliminando viaje..."
      >
        <p className={styles.modalDescription}>
          ¿Seguro que deseas eliminar el viaje <strong>{trip.name}</strong>? Esta acción no se puede deshacer.
        </p>
        <div className={styles.modalFooter}>
          <button
            type="button"
            className={styles.chipButton}
            onClick={onClose}
          >
            Cancelar
          </button>
          <button
            type="button"
            className={`${styles.chipButton} ${styles.chipDanger}`}
            onClick={handleConfirm}
            disabled={isPending}
          >
            Sí, eliminar
          </button>
        </div>
      </RequestState>
    </ModalShell>
  );
}

type TooltipWrapperProps = {
  children: React.ReactNode;
  message: string;
  disabled?: boolean;
};

function TooltipWrapper({ children, message, disabled = false }: TooltipWrapperProps) {
  const [visible, setVisible] = useState(false);

  if (disabled) {
    return <>{children}</>;
  }

  return (
    <div
      className={styles.tooltipWrapper}
      onMouseEnter={() => setVisible(true)}
      onMouseLeave={() => setVisible(false)}
      onFocus={() => setVisible(true)}
      onBlur={() => setVisible(false)}
    >
      {children}
      {visible ? <div className={styles.tooltipContent}>{message}</div> : null}
    </div>
  );
}


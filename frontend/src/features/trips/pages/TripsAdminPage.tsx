import { useEffect, useMemo, useRef, useState } from "react";

import { CommonLayout } from "@/components/CommonLayout/CommonLayout";
import { RequestState } from "@/components/ui/RequestState/RequestState";
import { useLocation } from "wouter";
import { useAppForm } from "@/config/use-app-form";
import {
  TripCreateDTOSchema,
  type BulkAssignResultDTO,
  type TripCreateDTO,
  type TripSummaryDTO,
  UserAssignBulkDTOSchema,
} from "@/features/trips/types/trips-dtos";
import {
  useAssignUsersBulk,
  useCreateTrip,
  useDeleteTrip,
  useTripStudentsAdmin,
  useTrips,
  useUnassignTripStudent,
} from "@/features/trips/services/trips-service";
import { isCanonicalStudentDni, normalizeStudentDniInput } from "@/lib/dni";
import { createGsapMatchMedia, getMotionProfile, gsap, useGSAP } from "@/lib/gsap";

import styles from "./TripsAdminPage.module.css";

const currencyFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "ARS",
});

const usdFormatter = new Intl.NumberFormat("es-AR", {
  style: "currency",
  currency: "USD",
});

type ModalState =
  | { type: "none" }
  | { type: "create" }
  | { type: "assign"; tripId: number; tripName: string }
  | { type: "students"; tripId: number; tripName: string }
  | { type: "delete"; trip: TripSummaryDTO };

export function TripsAdminPage() {
  const { data, isLoading, error, refetch } = useTrips();
  const [modalState, setModalState] = useState<ModalState>({ type: "none" });
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const sectionRef = useRef<HTMLElement | null>(null);

  const openCreate = () => setModalState({ type: "create" });
  const closeModal = () => setModalState({ type: "none" });

  const handleAssign = (trip: TripSummaryDTO) =>
    setModalState({ type: "assign", tripId: trip.id, tripName: trip.name });
  const handleManageStudents = (trip: TripSummaryDTO) =>
    setModalState({ type: "students", tripId: trip.id, tripName: trip.name });
  const handleDelete = (trip: TripSummaryDTO) => setModalState({ type: "delete", trip });

  const trips = data ?? [];

  const totalTrips = trips.length;

  useEffect(() => {
    if (!successMessage) {
      return;
    }

    const timeoutId = window.setTimeout(() => {
      setSuccessMessage(null);
    }, 4000);

    return () => window.clearTimeout(timeoutId);
  }, [successMessage]);

  const handleAssignSuccess = (result: BulkAssignResultDTO) => {
    const parts: string[] = [];

    if (result.assignedCount > 0) {
      parts.push(
        `${result.assignedCount} alumno${result.assignedCount === 1 ? "" : "s"} asignado${result.assignedCount === 1 ? "" : "s"}`,
      );
    }

    if (result.pendingCount > 0) {
      parts.push(
        `${result.pendingCount} DNI${result.pendingCount === 1 ? "" : "s"} pendiente${result.pendingCount === 1 ? "" : "s"}`,
      );
    }

    setSuccessMessage(
      parts.length > 0
        ? `Asignacion realizada con exito: ${parts.join(" · ")}.`
        : "Asignacion realizada con exito.",
    );
  };

  useGSAP(
    () => {
      if (!sectionRef.current) {
        return;
      }

      const section = sectionRef.current;
      const motion = getMotionProfile();

      const mm = createGsapMatchMedia();

      if (!mm) {
        gsap.set(
          [
            section.querySelector(`.${styles.header}`),
            ...Array.from(section.querySelectorAll(`.${styles.card}`)),
            section.querySelector(`.${styles.successBanner}`),
          ],
          { clearProps: "opacity,visibility,transform" },
        );
        return;
      }

      mm.add("(prefers-reduced-motion: reduce)", () => {
        gsap.set(
          [
            section.querySelector(`.${styles.header}`),
            ...Array.from(section.querySelectorAll(`.${styles.card}`)),
            section.querySelector(`.${styles.successBanner}`),
          ],
          { clearProps: "opacity,visibility,transform" },
        );
      });

      mm.add("(prefers-reduced-motion: no-preference)", () => {
        const header = section.querySelector(`.${styles.header}`);
        const successBanner = section.querySelector(`.${styles.successBanner}`);
        const cards = section.querySelectorAll(`.${styles.card}`);

        if (header) {
          gsap.fromTo(
            header,
            { autoAlpha: 0, y: motion.distanceSm },
            { autoAlpha: 1, y: 0, duration: motion.durationBase, ease: "power2.out" },
          );
        }

        if (successBanner) {
          gsap.fromTo(
            successBanner,
            { autoAlpha: 0, y: -motion.distanceSm },
            {
              autoAlpha: 1,
              y: 0,
              duration: motion.durationFast,
              ease: "power2.out",
              delay: motion.durationFast / 2,
            },
          );
        }

        if (cards && cards.length > 0) {
          gsap.fromTo(
            cards,
            { autoAlpha: 0, y: motion.distanceMd },
            {
              autoAlpha: 1,
              y: 0,
              duration: motion.durationBase,
              stagger: motion.staggerBase,
              ease: "power2.out",
              delay: motion.durationFast / 2,
              clearProps: "opacity,visibility,transform",
            },
          );
        }
      });

      return () => mm.revert();
    },
    {
      dependencies: [isLoading, trips.length, successMessage],
      scope: sectionRef,
      revertOnUpdate: true,
    },
  );

  return (
    <CommonLayout>
      <section ref={sectionRef} className={styles.page}>
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

        {successMessage ? (
          <div className={styles.successBanner} role="status" aria-live="polite">
            <span className={styles.successIcon} aria-hidden="true">✓</span>
            <div className={styles.successContent}>
              <strong className={styles.successTitle}>Asignacion completada</strong>
              <p className={styles.successMessage}>{successMessage}</p>
            </div>
          </div>
        ) : null}

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
                  onAssign={handleAssign}
                  onManageStudents={handleManageStudents}
                  onDelete={handleDelete}
                />
              ))}
            </div>
          )}
        </RequestState>

        {modalState.type === "create" ? (
          <TripModalCreate onClose={closeModal} />
        ) : modalState.type === "assign" ? (
          <AssignUsersModal
            tripId={modalState.tripId}
            tripName={modalState.tripName}
            onSuccess={handleAssignSuccess}
            onClose={closeModal}
          />
        ) : modalState.type === "students" ? (
          <ManageTripStudentsModal
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
  onAssign: (trip: TripSummaryDTO) => void;
  onManageStudents: (trip: TripSummaryDTO) => void;
  onDelete: (trip: TripSummaryDTO) => void;
};

function TripCard({ trip, onAssign, onManageStudents, onDelete }: TripCardProps) {
  const [, setLocation] = useLocation();
  const status = trip.assignedParticipantsCount > 0 ? "active" : "configuring";
  const isActive = status === "active";

  const tripAmountLabel =
    trip.currency === "USD"
      ? `${usdFormatter.format(trip.totalAmount)} USD`
      : `${currencyFormatter.format(trip.totalAmount)} ARS`;

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
              if (trip.assignedParticipantsCount > 0) {
                setLocation(`/trips/${trip.id}/spreadsheet`);
              }
            }}
          >
            {trip.name}
          </button>
          <p className={styles.cardMeta}>
            {trip.installmentsCount} cuotas · {trip.assignedParticipantsCount} participantes
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
        <span className={styles.value}>{tripAmountLabel}</span>
        <span className={styles.label}>Usuarios asignados</span>
        <span className={styles.value}>{trip.assignedParticipantsCount}</span>
      </div>
      <div className={styles.cardFooter}>
        <button
          type="button"
          className={styles.chipButton}
          onClick={() => onAssign(trip)}
          aria-label={`Asignar usuarios al viaje ${trip.name}`}
        >
          👥 Asignar usuarios
        </button>
        <button
          type="button"
          className={styles.chipButton}
          onClick={() => onManageStudents(trip)}
          aria-label={`Ver chicos del viaje ${trip.name}`}
        >
          🧾 Ver alumnos
        </button>
        <TooltipWrapper
          disabled={trip.assignedParticipantsCount > 0}
          message="Asigna usuarios primero para ver la plantilla"
        >
          <button
            type="button"
            className={`${styles.chipButton} ${
              trip.assignedParticipantsCount === 0 ? styles.chipDisabled : ""
            }`}
            onClick={() => {
              if (trip.assignedParticipantsCount > 0) {
                setLocation(`/trips/${trip.id}/spreadsheet`);
              }
            }}
            aria-label={
              trip.assignedParticipantsCount > 0
                ? `Ver plantilla del viaje ${trip.name}`
                : `No se puede ver la plantilla de ${trip.name} porque aún no tiene usuarios asignados`
            }
            disabled={trip.assignedParticipantsCount === 0}
          >
            📊 Ver plantilla
          </button>
        </TooltipWrapper>
        <button
          type="button"
          className={`${styles.chipButton} ${styles.chipDanger}`}
          onClick={() => onDelete(trip)}
          aria-label={`Eliminar viaje ${trip.name}`}
        >
          🗑 Eliminar
        </button>
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

  useGSAP(
    () => {
      if (!overlayRef.current || !dialogRef.current) {
        return;
      }

      const motion = getMotionProfile();
      const mm = createGsapMatchMedia();

      if (!mm) {
        gsap.set([overlayRef.current, dialogRef.current], {
          clearProps: "opacity,visibility,transform",
        });
        return;
      }

      mm.add("(prefers-reduced-motion: reduce)", () => {
        gsap.set([overlayRef.current, dialogRef.current], {
          clearProps: "opacity,visibility,transform",
        });
      });

      mm.add("(prefers-reduced-motion: no-preference)", () => {
        gsap.fromTo(
          overlayRef.current,
          { autoAlpha: 0 },
          { autoAlpha: 1, duration: motion.durationFast, ease: "power1.out" },
        );
        gsap.fromTo(
          dialogRef.current,
          { autoAlpha: 0, y: motion.distanceMd, scale: motion.isCompact ? 0.992 : 0.985 },
          {
            autoAlpha: 1,
            y: 0,
            scale: 1,
            duration: motion.durationBase,
            ease: "power2.out",
            clearProps: "opacity,visibility,transform",
          },
        );
      });

      return () => mm.revert();
    },
    { scope: overlayRef },
  );

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
  const { mutateAsync, isPending } = useCreateTrip();
  const createDefaults: TripCreateDTO = {
    name: "",
    totalAmount: 0,
    currency: "ARS",
    installmentsCount: 1,
    dueDay: 1,
    yellowWarningDays: 0,
    fixedFineAmount: 0,
    retroactiveActive: false,
    firstDueDate: "",
  };

  const formData = useAppForm({
    defaultValues: createDefaults,
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
      <RequestState isLoading={isPending} loadingLabel="Creando viaje...">
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
                      label="Monto total"
                      placeholder="Ej: 1500000"
                      autoComplete="off"
                      min={0}
                      step={0.01}
                    />
                  )}
                />
                <formData.AppField
                  name="currency"
                  children={(field) => (
                    <label className={styles.fieldGroup}>
                      <span className={styles.label}>Moneda del viaje</span>
                      <select
                        name={field.name}
                        className={styles.selectField}
                        value={field.state.value}
                        onChange={(event) => field.handleChange(event.target.value as "ARS" | "USD")}
                      >
                        <option value="ARS">Pesos (ARS)</option>
                        <option value="USD">Dólares (USD)</option>
                      </select>
                    </label>
                  )}
                />
              </div>

              <div className={styles.inlineFields}>
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

              {/* Mutation errors handled by global MutationCache toast */}
            </div>
          </formData.FormContainer>
        </formData.AppForm>
      </RequestState>
    </ModalShell>
  );
}

type AssignUsersModalProps = {
  tripId: number;
  tripName: string;
  onSuccess: (result: BulkAssignResultDTO) => void;
  onClose: () => void;
};

function AssignUsersModal({ tripId, tripName, onSuccess, onClose }: AssignUsersModalProps) {
  const { mutateAsync, isPending, data } = useAssignUsersBulk();
  const [rawInput, setRawInput] = useState("");

  const parsedDnis = useMemo(() => {
    const matches = rawInput.match(/(?<!\d)(?:\d[.\-\s]?){7,8}(?!\d)/g) ?? [];

    return matches
      .map((match) => normalizeStudentDniInput(match))
      .filter((dni) => isCanonicalStudentDni(dni))
      .slice(0, 500);
  }, [rawInput]);

  const parsedDtoState = useMemo(() => {
    try {
      return {
        data: UserAssignBulkDTOSchema.parse({ studentDnis: parsedDnis }),
        issues: [] as string[],
      };
    } catch (currentError) {
      if (currentError instanceof Error && "issues" in currentError) {
        const issues = (currentError as { issues?: Array<{ message?: string }> }).issues ?? [];
        return {
          data: null,
          issues: issues
            .map((issue) => issue.message)
            .filter((message): message is string => Boolean(message)),
        };
      }

      return {
        data: null,
        issues: [],
      };
    }
  }, [parsedDnis]);

  const hasValidIds = parsedDtoState.data !== null && parsedDtoState.data.studentDnis.length > 0;

  const handleSubmit = async () => {
    if (!parsedDtoState.data) {
      return;
    }
    try {
      await mutateAsync({ id: tripId, data: parsedDtoState.data });
    } catch {
      // RequestState renders the API error; we only prevent an unhandled rejection here.
    }
  };

  useEffect(() => {
    if (data && !isPending) {
      onSuccess(data);
      onClose();
    }
  }, [data, isPending, onClose, onSuccess]);

  return (
    <ModalShell
      title={`Asignar usuarios a ${tripName}`}
      description="Pega los DNIs de los alumnos separados por coma, espacio o salto de línea. Si un DNI todavía no tiene padre registrado, quedará pendiente hasta que el padre cree su cuenta. Máximo 500 DNIs, sin duplicados."
      onClose={onClose}
    >
      <RequestState
        isLoading={isPending}
        loadingLabel="Asignando alumnos al viaje..."
      >
        <>
          <label className={styles.fieldGroup}>
            <span className={styles.label}>DNIs de alumnos</span>
            <textarea
              className={styles.idsTextArea}
              value={rawInput}
              onChange={(event) => setRawInput(event.target.value)}
              placeholder="Ej: 45678901, 45678902 o uno por línea"
            />
            <p className={styles.idsHelper}>
              Ingresá los DNIs de los alumnos. Acepta puntos, guiones y espacios; se envían sin formato. Los DNIs sin padre asociado quedarán pendientes.
            </p>
            <p className={styles.idsPreview}>
              DNIs válidos detectados: {parsedDnis.length}
              {parsedDnis.length > 0 ? ` · Se enviarán ${Math.min(parsedDnis.length, 500)} DNIs` : ""}
            </p>
            {parsedDtoState.issues.length > 0 ? (
              <ul className={styles.fieldErrorList}>
                {parsedDtoState.issues.map((message) => (
                  <li key={message}>{message}</li>
                ))}
              </ul>
            ) : null}
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

type ManageTripStudentsModalProps = {
  tripId: number;
  tripName: string;
  onClose: () => void;
};

function ManageTripStudentsModal({ tripId, tripName, onClose }: ManageTripStudentsModalProps) {
  const { data, isLoading, error } = useTripStudentsAdmin(tripId);
  const { mutateAsync, isPending, error: unassignError } = useUnassignTripStudent();
  const [confirmingDni, setConfirmingDni] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!successMessage) {
      return;
    }

    const timeoutId = window.setTimeout(() => {
      setSuccessMessage(null);
    }, 4000);

    return () => window.clearTimeout(timeoutId);
  }, [successMessage]);

  const studentItems = data ?? [];

  const handleConfirmUnassign = async (studentDni: string) => {
    try {
      await mutateAsync({ tripId, studentDni });
      setConfirmingDni(null);
      setSuccessMessage(`El DNI ${studentDni} fue desasignado del viaje.`);
    } catch {
      // The mutation error is rendered in the modal itself.
    }
  };

  return (
    <ModalShell
      title={`Chicos cargados en ${tripName}`}
      description="Ves tanto DNIs pendientes como alumnos ya reclamados. Si desasignás un DNI, se eliminan sus cuotas o pendientes de este viaje."
      onClose={onClose}
    >
      <RequestState
        isLoading={isLoading}
        error={error ?? null}
        loadingLabel="Cargando chicos del viaje..."
      >
        <>
          {successMessage ? <p className={styles.inlineSuccess}>{successMessage}</p> : null}
          {unassignError ? <p className={styles.inlineError}>{unassignError.message}</p> : null}
          {!isLoading && !error && studentItems.length === 0 ? (
            <p className={styles.emptyStudentsState}>Todavía no hay DNIs cargados en este viaje.</p>
          ) : null}

          {studentItems.length > 0 ? (
            <div className={styles.studentAdminList}>
              {studentItems.map((student) => {
                const isPendingStudent = student.status === "PENDING";
                const isConfirming = confirmingDni === student.studentDni;

                return (
                  <article key={`${student.status}-${student.studentDni}`} className={styles.studentAdminCard}>
                    <div className={styles.studentAdminHeader}>
                      <div className={styles.studentAdminTitleBlock}>
                        <strong className={styles.studentAdminDni}>{student.studentDni}</strong>
                        <span
                          className={`${styles.studentStatusBadge} ${
                            isPendingStudent ? styles.studentStatusPending : styles.studentStatusAssigned
                          }`}
                        >
                          {isPendingStudent ? "Pendiente" : "Reclamado"}
                        </span>
                      </div>
                      <button
                        type="button"
                        className={`${styles.chipButton} ${styles.chipDanger}`}
                        onClick={() => setConfirmingDni(student.studentDni)}
                        disabled={isPending}
                      >
                        Desasignar
                      </button>
                    </div>

                    <div className={styles.studentAdminMetaGrid}>
                      <div>
                        <span className={styles.metaLabel}>Alumno</span>
                        <strong>{student.studentName ?? "Todavía sin reclamar"}</strong>
                      </div>
                      <div>
                        <span className={styles.metaLabel}>Colegio</span>
                        <strong>{student.schoolName ?? "Sin dato"}</strong>
                      </div>
                      <div>
                        <span className={styles.metaLabel}>Curso</span>
                        <strong>{student.courseName ?? "Sin dato"}</strong>
                      </div>
                      <div>
                        <span className={styles.metaLabel}>Padre</span>
                        <strong>{student.parentFullName ?? "Sin vincular"}</strong>
                      </div>
                      <div>
                        <span className={styles.metaLabel}>Email</span>
                        <strong>{student.parentEmail ?? "Sin vincular"}</strong>
                      </div>
                      <div>
                        <span className={styles.metaLabel}>Cuotas generadas</span>
                        <strong>{student.installmentsCount}</strong>
                      </div>
                    </div>

                    {isConfirming ? (
                      <div className={styles.confirmBox} role="alert">
                        <strong>Advertencia:</strong> vas a desasignar el DNI {student.studentDni} de este viaje.
                        Esto elimina sus cuotas generadas o su pendiente actual y no se puede deshacer.
                        <div className={styles.confirmActions}>
                          <button
                            type="button"
                            className={`${styles.chipButton} ${styles.chipDanger}`}
                            onClick={() => handleConfirmUnassign(student.studentDni)}
                            disabled={isPending}
                          >
                            {isPending ? "Desasignando..." : "Sí, desasignar"}
                          </button>
                          <button
                            type="button"
                            className={styles.chipButton}
                            onClick={() => setConfirmingDni(null)}
                            disabled={isPending}
                          >
                            Cancelar
                          </button>
                        </div>
                      </div>
                    ) : null}
                  </article>
                );
              })}
            </div>
          ) : null}
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
  const { mutateAsync, isPending } = useDeleteTrip();

  const handleConfirm = async () => {
    await mutateAsync({ id: trip.id });
    onClose();
  };

  return (
    <ModalShell
      title="Eliminar viaje"
      description="Esta acción es permanente y eliminará el viaje junto con sus cuotas asociadas."
      onClose={onClose}
    >
      <RequestState
        isLoading={isPending}
        loadingLabel="Eliminando viaje..."
      >
        {trip.assignedParticipantsCount > 0 ? (
          <div className={styles.warningBox} role="alert">
            <strong>Advertencia:</strong> este viaje tiene {trip.assignedParticipantsCount} integrante{trip.assignedParticipantsCount === 1 ? "" : "s"} asignado{trip.assignedParticipantsCount === 1 ? "" : "s"}.
            Al eliminarlo también se borrarán todas sus cuotas generadas y el historial asociado a esas cuotas.
          </div>
        ) : null}
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

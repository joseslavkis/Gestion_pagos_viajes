package com.agencia.pagos.services;

import com.agencia.pagos.config.security.JwtService;
import com.agencia.pagos.config.security.JwtUserDetails;
import com.agencia.pagos.dtos.request.AdminCreateDTO;
import com.agencia.pagos.dtos.request.RefreshDTO;
import com.agencia.pagos.dtos.request.ResetPasswordDTO;
import com.agencia.pagos.dtos.request.StudentCreateDTO;
import com.agencia.pagos.dtos.request.UserCreateDTO;
import com.agencia.pagos.dtos.request.UserUpdateDTO;
import com.agencia.pagos.dtos.response.AdminUserDetailDTO;
import com.agencia.pagos.dtos.response.AdminUserInstallmentDTO;
import com.agencia.pagos.dtos.response.AdminUserSearchResultDTO;
import com.agencia.pagos.dtos.response.PaymentReceiptDTO;
import com.agencia.pagos.dtos.response.StudentDTO;
import com.agencia.pagos.dtos.response.TokenDTO;
import com.agencia.pagos.dtos.response.UserProfileDTO;
import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Installment;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.PaymentReceipt;
import com.agencia.pagos.entities.PasswordResetToken;
import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.Student;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.entities.user.UserCredentials;
import com.agencia.pagos.entities.refresh_token.RefreshToken;
import com.agencia.pagos.repositories.InstallmentRepository;
import com.agencia.pagos.repositories.PasswordResetTokenRepository;
import com.agencia.pagos.repositories.PaymentReceiptRepository;
import com.agencia.pagos.repositories.StudentRepository;
import com.agencia.pagos.repositories.UserRepository;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class UserService implements UserDetailsService {
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final InstallmentRepository installmentRepository;
    private final PaymentReceiptRepository paymentReceiptRepository;
    private final StudentRepository studentRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final InstallmentStatusResolver installmentStatusResolver;
    private final InstallmentUiStatusResolver installmentUiStatusResolver;
    private final EmailService emailService;

    @Autowired
    UserService(
            JwtService jwtService,
            PasswordEncoder passwordEncoder,
            UserRepository userRepository,
            InstallmentRepository installmentRepository,
            PaymentReceiptRepository paymentReceiptRepository,
            StudentRepository studentRepository,
            RefreshTokenService refreshTokenService,
            PasswordResetTokenRepository passwordResetTokenRepository,
            InstallmentStatusResolver installmentStatusResolver,
            InstallmentUiStatusResolver installmentUiStatusResolver,
            EmailService emailService
    ) {
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.installmentRepository = installmentRepository;
        this.paymentReceiptRepository = paymentReceiptRepository;
        this.studentRepository = studentRepository;
        this.refreshTokenService = refreshTokenService;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.installmentStatusResolver = installmentStatusResolver;
        this.installmentUiStatusResolver = installmentUiStatusResolver;
        this.emailService = emailService;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository
                .findByEmail(email)
                .orElseThrow(() -> {
                    var msg = String.format("Email '%s' not found", email);
                    return new UsernameNotFoundException(msg);
                });
    }

    public Optional<TokenDTO> createUser(UserCreateDTO data) {
        if (userRepository.findByEmail(data.email()).isPresent() || userRepository.existsByDni(data.dni())) {
            return Optional.empty();
        }

        validateStudentDnis(data.students());

        var user = data.asUser(passwordEncoder::encode, Role.USER);
        attachStudents(user, data.students());
        userRepository.save(user);
        return Optional.of(generateTokens(user));
    }

    public Optional<TokenDTO> createAdmin(AdminCreateDTO data) {
        if (userRepository.findByEmail(data.email()).isPresent() || userRepository.existsByDni(data.dni())) {
            return Optional.empty();
        }

        var user = data.asUser(passwordEncoder::encode);
        userRepository.save(user);
        return Optional.of(generateTokens(user));
    }

    public Optional<TokenDTO> loginUser(UserCredentials data) {
        Optional<User> maybeUser = userRepository.findByEmail(data.email());
        return maybeUser
                .filter(user -> passwordEncoder.matches(data.password(), user.getPassword()))
                .map(this::generateTokens);
    }

    public Optional<TokenDTO> refresh(RefreshDTO data) {
        return refreshTokenService.findByValue(data.refreshToken())
                .map(RefreshToken::user)
                .map(this::generateTokens);
    }

    public void requestPasswordReset(String email) {
        Optional<User> maybeUser = userRepository.findByEmail(email);
        if (maybeUser.isEmpty()) {
            return;
        }

        User user = maybeUser.get();
        passwordResetTokenRepository.deleteByUser(user);

        String token = UUID.randomUUID().toString();
        PasswordResetToken passwordResetToken = PasswordResetToken.builder()
                .token(token)
                .user(user)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .used(false)
                .build();

        passwordResetTokenRepository.save(passwordResetToken);
        emailService.sendPasswordResetEmail(user.getEmail(), user.getName(), token);
    }

    public void resetPassword(ResetPasswordDTO dto) {
        PasswordResetToken token = passwordResetTokenRepository.findByToken(dto.token())
                .orElseThrow(() -> new IllegalStateException("El enlace de recuperación no es válido."));

        if (token.isUsed()) {
            throw new IllegalStateException("Este enlace ya fue utilizado.");
        }

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("El enlace de recuperación expiró. Solicitá uno nuevo.");
        }

        User user = token.getUser();
        user.setPassword(passwordEncoder.encode(dto.newPassword()));
        userRepository.save(user);

        token.setUsed(true);
        passwordResetTokenRepository.save(token);
        refreshTokenService.deleteByUser(user);
    }

    public Optional<UserProfileDTO> getUserProfileById(Long id) {
        return userRepository.findById(id)
                .map(user -> new UserProfileDTO(
                        user.getId(),
                        user.getEmail(),
                        user.getName(),
                        user.getLastname(),
                user.getRole()
                ));
    }

    @Transactional(readOnly = true)
    public List<AdminUserSearchResultDTO> searchUsersForAdmin(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < 2) {
            return List.of();
        }

        return userRepository.searchByRoleAndQuery(Role.USER, query, PageRequest.of(0, 20)).stream()
                .map(user -> new AdminUserSearchResultDTO(
                        user.getId(),
                        user.getEmail(),
                        user.getName(),
                        user.getLastname(),
                        user.getDni(),
                        user.getPhone(),
                        user.getRole(),
                        user.getStudents().size()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminUserDetailDTO getAdminUserDetail(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id " + id));

        if (user.getRole() != Role.USER) {
            throw new EntityNotFoundException("User not found with id " + id);
        }

        List<StudentDTO> students = user.getStudents().stream()
                .sorted(Comparator.comparing(Student::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toStudentDTO)
                .toList();

        List<Installment> installments = installmentRepository.findByUserIdWithTrip(user.getId());
        List<Long> installmentIds = installments.stream()
                .map(Installment::getId)
                .toList();

        Map<Long, PaymentReceipt> latestReceiptByInstallmentId = installmentIds.isEmpty()
                ? Map.of()
                : paymentReceiptRepository.findByInstallmentIdIn(installmentIds).stream()
                        .collect(Collectors.toMap(
                                receipt -> receipt.getInstallment().getId(),
                                Function.identity(),
                                (existing, ignored) -> existing
                        ));

        List<AdminUserInstallmentDTO> installmentDTOs = installments.stream()
                .map(installment -> toAdminInstallmentDTO(
                        installment,
                        latestReceiptByInstallmentId.get(installment.getId())
                ))
                .sorted(Comparator
                        .comparing(AdminUserInstallmentDTO::tripName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(
                                AdminUserInstallmentDTO::studentName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                        )
                        .thenComparing(AdminUserInstallmentDTO::installmentNumber))
                .toList();

        List<PaymentReceiptDTO> receiptDTOs = paymentReceiptRepository.findByInstallmentUserIdWithContext(user.getId()).stream()
                .map(this::toPaymentReceiptDTO)
                .toList();

        return new AdminUserDetailDTO(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getLastname(),
                user.getDni(),
                user.getPhone(),
                user.getRole(),
                students,
                installmentDTOs,
                receiptDTOs
        );
    }

    /**
     * Verifica autorización (admin o propietario del perfil) y devuelve el perfil.
     * Lanza {@link AccessDeniedException} si el solicitante no tiene permiso
     * y {@link EntityNotFoundException} si el usuario destino no existe.
     */
    @Transactional(readOnly = true)
    public UserProfileDTO getProfileWithAuthorization(Long id, String requesterEmail) {
        User requester = getUserByEmail(requesterEmail);
        boolean isAdmin = requester.getRole() == Role.ADMIN;
        boolean isOwner = requester.getId().equals(id);

        if (!isAdmin && !isOwner) {
            throw new AccessDeniedException("Access denied: you can only view your own profile");
        }

        return getUserProfileById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id " + id));
    }

    public Optional<User> deactivateUser(Long id) {
        Optional<User> user = userRepository.findById(id);
        if (user.isPresent()) {
            user.get().setActive(false);
            userRepository.save(user.get());
            refreshTokenService.deleteByUser(user.get());
        }
        return user;
    }

    private TokenDTO generateTokens(User user) {
        String accessToken = jwtService.createToken(new JwtUserDetails(
                user.getUsername(),
                user.getRole().name()
        ));
        RefreshToken refreshToken = refreshTokenService.createFor(user);
        return new TokenDTO(accessToken, refreshToken.value());
    }

    public User getUserByEmail(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new EntityNotFoundException("User not found"));
    }

    public User updateAdmin(Long id, UserUpdateDTO userDTO) {
        User foundUser = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Admin not found"));

        if (Role.ADMIN != foundUser.getRole()) {
            throw new AccessDeniedException("Target user is not an admin");
        }

        return applyUpdates(foundUser, userDTO);
    }

    public User updateUser(UserUpdateDTO userDTO, Long id) {
        User foundUser = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        return applyUpdates(foundUser, userDTO);
    }

    @Transactional(readOnly = true)
    public List<StudentDTO> getStudentsForCurrentUser(String email) {
        User user = getUserByEmail(email);
        return studentRepository.findByParentId(user.getId()).stream()
                .sorted(Comparator.comparing(Student::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::toStudentDTO)
                .toList();
    }

    public StudentDTO addStudentForCurrentUser(String email, StudentCreateDTO dto) {
        User user = getUserByEmail(email);

        if (studentRepository.existsByDni(dto.dni())) {
            throw new IllegalStateException("El DNI de alumno " + dto.dni() + " ya está registrado en el sistema");
        }

        Student student = Student.builder()
                .parent(user)
                .name(dto.name())
                .dni(dto.dni())
                .schoolName(dto.schoolName())
                .courseName(dto.courseName())
                .build();

        return toStudentDTO(studentRepository.save(student));
    }

    public void deleteStudentForCurrentUser(String email, Long studentId) {
        User user = getUserByEmail(email);
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new EntityNotFoundException("Student not found with id " + studentId));

        if (!student.getParent().getId().equals(user.getId())) {
            throw new AccessDeniedException("No podés eliminar un alumno que no te pertenece");
        }

        if (installmentRepositoryExistsByStudentId(studentId)) {
            throw new IllegalStateException("No se puede eliminar un alumno con cuotas asignadas");
        }

        studentRepository.delete(student);
    }

    private User applyUpdates(User user, UserUpdateDTO dto) {
        if (dto.name() != null) {
            user.setName(dto.name());
        }
        if (dto.lastname() != null) {
            user.setLastname(dto.lastname());
        }
        if (dto.password() != null) {
            user.setPassword(passwordEncoder.encode(dto.password()));
        }
        return userRepository.save(user);
    }

    private void validateStudentDnis(List<StudentCreateDTO> students) {
        for (StudentCreateDTO student : students) {
            if (studentRepository.existsByDni(student.dni())) {
                throw new IllegalStateException("El DNI de alumno " + student.dni() + " ya está registrado en el sistema");
            }
        }
    }

    private void attachStudents(User user, List<StudentCreateDTO> students) {
        for (StudentCreateDTO studentDto : students) {
            Student student = Student.builder()
                    .parent(user)
                    .name(studentDto.name())
                    .dni(studentDto.dni())
                    .schoolName(studentDto.schoolName())
                    .courseName(studentDto.courseName())
                    .build();
            user.getStudents().add(student);
        }
    }

    private StudentDTO toStudentDTO(Student student) {
        return new StudentDTO(
                student.getId(),
                student.getName(),
                student.getDni(),
                student.getSchoolName(),
                student.getCourseName()
        );
    }

    private AdminUserInstallmentDTO toAdminInstallmentDTO(
            Installment installment,
            PaymentReceipt latestReceipt
    ) {
        Student student = installment.getStudent();
        int yellowDays = installment.getTrip().getYellowWarningDays() == null
                ? 0
                : installment.getTrip().getYellowWarningDays();

        InstallmentStatus effectiveStatus = installmentStatusResolver.computeEffective(
                installment.getStatus(),
                installment.getDueDate(),
                yellowDays
        );
        InstallmentUiStatus uiStatus = installmentUiStatusResolver.resolve(
                effectiveStatus,
                latestReceipt != null ? latestReceipt.getStatus() : null,
                installment.getDueDate(),
                yellowDays,
                installment.getPaidAmount(),
                installment.getTotalDue()
        );

        return new AdminUserInstallmentDTO(
                installment.getTrip().getId(),
                installment.getTrip().getName(),
                installment.getTrip().getCurrency(),
                student != null ? student.getId() : null,
                student != null ? student.getName() : null,
                student != null ? student.getDni() : null,
                student != null ? student.getSchoolName() : null,
                student != null ? student.getCourseName() : null,
                installment.getId(),
                installment.getInstallmentNumber(),
                installment.getDueDate(),
                installment.getTotalDue(),
                installment.getPaidAmount(),
                effectiveStatus,
                latestReceipt != null ? latestReceipt.getStatus() : null,
                uiStatus.code(),
                uiStatus.label(),
                uiStatus.tone(),
                latestReceipt != null ? latestReceipt.getAdminObservation() : null
        );
    }

    private PaymentReceiptDTO toPaymentReceiptDTO(PaymentReceipt receipt) {
        return new PaymentReceiptDTO(
                receipt.getId(),
                receipt.getInstallment().getId(),
                receipt.getInstallment().getInstallmentNumber(),
                receipt.getReportedAmount(),
                receipt.getPaymentCurrency(),
                receipt.getExchangeRate(),
                receipt.getAmountInTripCurrency(),
                receipt.getReportedPaymentDate(),
                receipt.getPaymentMethod(),
                receipt.getStatus(),
                receipt.getFileKey(),
                receipt.getAdminObservation(),
                receipt.getBankAccount() != null ? receipt.getBankAccount().getId() : null,
                receipt.getBankAccount() != null ? formatBankAccountDisplay(receipt.getBankAccount()) : null,
                receipt.getBankAccount() != null ? receipt.getBankAccount().getAlias() : null
        );
    }

    private String formatBankAccountDisplay(BankAccount bankAccount) {
        return bankAccount.getBankName() + " - " + bankAccount.getAccountLabel();
    }

    private boolean installmentRepositoryExistsByStudentId(Long studentId) {
        return installmentRepository.existsByStudentId(studentId);
    }
}

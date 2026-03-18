package com.agencia.pagos.entities;

import com.agencia.pagos.entities.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trips")
public class Trip {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal totalAmount;

	@Column(nullable = false)
	private Integer installmentsCount;

	@Column(nullable = false)
	private Integer dueDay;

	@Column(nullable = false)
	private Integer yellowWarningDays;

	@Column(nullable = false, precision = 10, scale = 2)
	private BigDecimal fixedFineAmount;

	@Column(name = "is_retroactive_active", nullable = false)
	private Boolean retroactiveActive = true;

	@Column(name = "first_due_date", nullable = false)
	private LocalDate firstDueDate;

	@ManyToMany
	@JoinTable(
			name = "trip_user",
			joinColumns = @JoinColumn(name = "trip_id"),
			inverseJoinColumns = @JoinColumn(name = "user_id"),
			uniqueConstraints = @UniqueConstraint(columnNames = {"trip_id", "user_id"})
	)
	private List<User> assignedUsers = new ArrayList<>();

	@OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Installment> installments = new ArrayList<>();

	public Trip() {
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public BigDecimal getTotalAmount() {
		return totalAmount;
	}

	public void setTotalAmount(BigDecimal totalAmount) {
		this.totalAmount = totalAmount;
	}

	public Integer getInstallmentsCount() {
		return installmentsCount;
	}

	public void setInstallmentsCount(Integer installmentsCount) {
		this.installmentsCount = installmentsCount;
	}

	public Integer getDueDay() {
		return dueDay;
	}

	public void setDueDay(Integer dueDay) {
		this.dueDay = dueDay;
	}

	public Integer getYellowWarningDays() {
		return yellowWarningDays;
	}

	public void setYellowWarningDays(Integer yellowWarningDays) {
		this.yellowWarningDays = yellowWarningDays;
	}

	public BigDecimal getFixedFineAmount() {
		return fixedFineAmount;
	}

	public void setFixedFineAmount(BigDecimal fixedFineAmount) {
		this.fixedFineAmount = fixedFineAmount;
	}

	public Boolean getRetroactiveActive() {
		return retroactiveActive;
	}

	public void setRetroactiveActive(Boolean retroactiveActive) {
		this.retroactiveActive = retroactiveActive;
	}

	public LocalDate getFirstDueDate() {
		return firstDueDate;
	}

	public void setFirstDueDate(LocalDate firstDueDate) {
		this.firstDueDate = firstDueDate;
	}

	public List<User> getAssignedUsers() {
		return assignedUsers;
	}

	public void setAssignedUsers(List<User> assignedUsers) {
		this.assignedUsers = assignedUsers;
	}

	public List<Installment> getInstallments() {
		return installments;
	}

	public void setInstallments(List<Installment> installments) {
		this.installments = installments;
	}
}

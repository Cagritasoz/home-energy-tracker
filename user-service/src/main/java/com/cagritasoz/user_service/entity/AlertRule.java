package com.cagritasoz.user_service.entity;

import com.cagritasoz.user_service.model.AlertScope;
import com.cagritasoz.user_service.model.EvaluationWindow;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

// Mirrors V3's alert_rules table. Lifecycle is subordinate to the
// user - the DB FK is ON DELETE CASCADE, so deleting a user clears their rules.
@Entity
@Table(
        name = "alert_rules",
        // Not enforced by ddl-auto=validate (it checks columns, not constraints), but kept in
        // sync with V3's UNIQUE so the mapping tells the truth and a test using create-drop
        // reproduces it. At most one rule per (user, window, scope).
        uniqueConstraints = @UniqueConstraint(
                name = "uq_alert_rules_user_window_scope",
                columnNames = {"user_id", "evaluation_window", "scope"}
        )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @ManyToOne rather than a bare Long userId (device-service's Device uses the bare id only
    // because it points across a service boundary; this is intra-service, so a real association
    // is fine). LAZY so listing rules doesn't drag a full User row along per rule - the service
    // layer should use EntityManager.getReference(User.class, id) when creating a rule to avoid
    // an extra SELECT. Excluded from equals/hashCode/toString: touching a lazy proxy there is
    // the classic JPA lazy-init landmine.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_alert_rules_user")
    )
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private User user;

    // Human label shown in the alert notification and the settings UI ("Daily budget"). The
    // service can synthesize one ("1 hour / 5.0 kWh") when the client omits it.
    @Column(length = 100, nullable = false)
    private String name;

    @Column(name = "evaluation_window", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private EvaluationWindow evaluationWindow;

    // BigDecimal + NUMERIC(10,3), not double - a user-entered, echoed-back, billing-adjacent
    // value. precision/scale here must match V3's NUMERIC(10,3).
    @Column(name = "threshold_kwh", precision = 10, scale = 3, nullable = false)
    private BigDecimal thresholdKwh;

    // @Builder.Default: without it the builder path leaves this null (Lombok ignores the field
    // initializer) and null hits the NOT NULL column.
    @Builder.Default
    @Column(length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private AlertScope scope = AlertScope.ALL_DEVICES;

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

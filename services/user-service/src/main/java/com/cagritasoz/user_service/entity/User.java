package com.cagritasoz.user_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // BIGSERIAL
    private Long id;

    @Column(name = "first_name", length = 100, nullable = false)
    private String firstName;

    @Column(name = "last_name", length = 100, nullable = false)
    private String lastName;

    @Column(length = 255, unique = true, nullable = false)
    private String email;

    @Column(columnDefinition = "TEXT")
    private String address;

    // Populated by Hibernate on INSERT, never on UPDATE (updatable = false) - mirrors the
    // DEFAULT now() in V2 so a row written outside the app still gets a value. TIMESTAMPTZ maps
    // to Instant. Audit columns never reaches the database as null as hibernate
    // populates them even though we do not include them while building a User object.
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Rewritten by Hibernate on every flush that dirties this entity.
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

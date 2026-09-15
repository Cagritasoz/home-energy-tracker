package com.cagritasoz.user_service.repository;

import com.cagritasoz.user_service.entity.AlertRule;
import com.cagritasoz.user_service.model.AlertScope;
import com.cagritasoz.user_service.model.EvaluationWindow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    // AlertRule has no "userId" field - it has a "user" @ManyToOne. Spring Data can't match
    // "UserId" as one property, so it backs off and splits it: "user" is a property of AlertRule,
    // and "id" is a property of THAT field's type (User) - both resolve, so the path becomes
    // user.id. Compiles to "WHERE a.user.id = :userId", which Hibernate turns into a plain
    // "WHERE user_id = ?" on alert_rules - the FK column already is the id, so this never joins
    // or loads a User row, it's only walking the property path, not the data.
    List<AlertRule> findByUserId(Long userId);

    Optional<AlertRule> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndEvaluationWindowAndScope(Long userId, EvaluationWindow window, AlertScope scope);

}

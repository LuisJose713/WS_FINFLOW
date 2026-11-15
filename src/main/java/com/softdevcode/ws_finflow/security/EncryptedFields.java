package com.softdevcode.ws_finflow.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

/** Explicit field contexts prevent ciphertext from being transplanted across columns. */
public final class EncryptedFields {

    @Converter @Component
    public static class TransactionDate implements AttributeConverter<java.time.LocalDateTime, String> {
        private final FieldCipher cipher;
        public TransactionDate(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(java.time.LocalDateTime value) {
            return value == null ? null : cipher.encrypt("transactions.date", value.toString());
        }
        public java.time.LocalDateTime convertToEntityAttribute(String value) {
            return value == null ? null : java.time.LocalDateTime.parse(cipher.decrypt("transactions.date", value).replace(' ', 'T'));
        }
    }
    @Converter @Component
    public static class GoalDeadline implements AttributeConverter<java.time.LocalDate, String> {
        private final FieldCipher cipher;
        public GoalDeadline(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(java.time.LocalDate value) {
            return value == null ? null : cipher.encrypt("goals.deadline", value.toString());
        }
        public java.time.LocalDate convertToEntityAttribute(String value) {
            return value == null ? null : java.time.LocalDate.parse(cipher.decrypt("goals.deadline", value).replace(' ', 'T'));
        }
    }
    @Converter @Component
    public static class PlannedDue implements AttributeConverter<java.time.LocalDate, String> {
        private final FieldCipher cipher;
        public PlannedDue(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(java.time.LocalDate value) {
            return value == null ? null : cipher.encrypt("planned_expenses.due_date", value.toString());
        }
        public java.time.LocalDate convertToEntityAttribute(String value) {
            return value == null ? null : java.time.LocalDate.parse(cipher.decrypt("planned_expenses.due_date", value).replace(' ', 'T'));
        }
    }
    @Converter @Component
    public static class PlannedReminder implements AttributeConverter<java.time.LocalDate, String> {
        private final FieldCipher cipher;
        public PlannedReminder(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(java.time.LocalDate value) {
            return value == null ? null : cipher.encrypt("planned_expenses.remind_at", value.toString());
        }
        public java.time.LocalDate convertToEntityAttribute(String value) {
            return value == null ? null : java.time.LocalDate.parse(cipher.decrypt("planned_expenses.remind_at", value).replace(' ', 'T'));
        }
    }
    private EncryptedFields() {}

    @Converter @Component
    public static class UserEmail implements AttributeConverter<String, String> {
        private final FieldCipher cipher;
        public UserEmail(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(String value) {
            return value == null ? null : cipher.encrypt("users.email", value);
        }
        public String convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("users.email", value);
            return plain;
        }
    }

    @Converter @Component
    public static class UserName implements AttributeConverter<String, String> {
        private final FieldCipher cipher;
        public UserName(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(String value) {
            return value == null ? null : cipher.encrypt("users.username", value);
        }
        public String convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("users.username", value);
            return plain;
        }
    }

    @Converter @Component
    public static class CategoryName implements AttributeConverter<String, String> {
        private final FieldCipher cipher;
        public CategoryName(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(String value) {
            return value == null ? null : cipher.encrypt("categories.name", value);
        }
        public String convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("categories.name", value);
            return plain;
        }
    }

    @Converter @Component
    public static class TransactionDescription implements AttributeConverter<String, String> {
        private final FieldCipher cipher;
        public TransactionDescription(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(String value) {
            return value == null ? null : cipher.encrypt("transactions.description", value);
        }
        public String convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("transactions.description", value);
            return plain;
        }
    }

    @Converter @Component
    public static class TransactionAmount implements AttributeConverter<Double, String> {
        private final FieldCipher cipher;
        public TransactionAmount(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(Double value) {
            return value == null ? null : cipher.encrypt("transactions.amount", value.toString());
        }
        public Double convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("transactions.amount", value);
            return Double.valueOf(plain);
        }
    }

    @Converter @Component
    public static class GoalTitle implements AttributeConverter<String, String> {
        private final FieldCipher cipher;
        public GoalTitle(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(String value) {
            return value == null ? null : cipher.encrypt("goals.title", value);
        }
        public String convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("goals.title", value);
            return plain;
        }
    }

    @Converter @Component
    public static class GoalDescription implements AttributeConverter<String, String> {
        private final FieldCipher cipher;
        public GoalDescription(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(String value) {
            return value == null ? null : cipher.encrypt("goals.description", value);
        }
        public String convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("goals.description", value);
            return plain;
        }
    }

    @Converter @Component
    public static class GoalTarget implements AttributeConverter<Double, String> {
        private final FieldCipher cipher;
        public GoalTarget(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(Double value) {
            return value == null ? null : cipher.encrypt("goals.target_amount", value.toString());
        }
        public Double convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("goals.target_amount", value);
            return Double.valueOf(plain);
        }
    }

    @Converter @Component
    public static class GoalCurrent implements AttributeConverter<Double, String> {
        private final FieldCipher cipher;
        public GoalCurrent(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(Double value) {
            return value == null ? null : cipher.encrypt("goals.current_amount", value.toString());
        }
        public Double convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("goals.current_amount", value);
            return Double.valueOf(plain);
        }
    }

    @Converter @Component
    public static class ContributionAmount implements AttributeConverter<Double, String> {
        private final FieldCipher cipher;
        public ContributionAmount(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(Double value) {
            return value == null ? null : cipher.encrypt("goal_contributions.amount", value.toString());
        }
        public Double convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("goal_contributions.amount", value);
            return Double.valueOf(plain);
        }
    }

    @Converter @Component
    public static class ContributionNote implements AttributeConverter<String, String> {
        private final FieldCipher cipher;
        public ContributionNote(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(String value) {
            return value == null ? null : cipher.encrypt("goal_contributions.note", value);
        }
        public String convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("goal_contributions.note", value);
            return plain;
        }
    }

    @Converter @Component
    public static class PlannedTitle implements AttributeConverter<String, String> {
        private final FieldCipher cipher;
        public PlannedTitle(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(String value) {
            return value == null ? null : cipher.encrypt("planned_expenses.title", value);
        }
        public String convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("planned_expenses.title", value);
            return plain;
        }
    }

    @Converter @Component
    public static class PlannedNotes implements AttributeConverter<String, String> {
        private final FieldCipher cipher;
        public PlannedNotes(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(String value) {
            return value == null ? null : cipher.encrypt("planned_expenses.notes", value);
        }
        public String convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("planned_expenses.notes", value);
            return plain;
        }
    }

    @Converter @Component
    public static class PlannedAmount implements AttributeConverter<BigDecimal, String> {
        private final FieldCipher cipher;
        public PlannedAmount(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(BigDecimal value) {
            return value == null ? null : cipher.encrypt("planned_expenses.amount", value.toString());
        }
        public BigDecimal convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("planned_expenses.amount", value);
            return new BigDecimal(plain);
        }
    }

    @Converter @Component
    public static class BudgetLimit implements AttributeConverter<BigDecimal, String> {
        private final FieldCipher cipher;
        public BudgetLimit(FieldCipher cipher) { this.cipher = cipher; }
        public String convertToDatabaseColumn(BigDecimal value) {
            return value == null ? null : cipher.encrypt("monthly_budgets.limit_amount", value.toString());
        }
        public BigDecimal convertToEntityAttribute(String value) {
            if (value == null) return null;
            String plain = cipher.decrypt("monthly_budgets.limit_amount", value);
            return new BigDecimal(plain);
        }
    }
}


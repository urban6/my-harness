package com.example.order.service;

/** 제약 위반을 제약명으로 식별한다 (Hibernate ConstraintViolationException#getConstraintName, 보조로 SQL 오류 메시지). */
public final class ConstraintNames {

    private ConstraintNames() {
    }

    public static boolean violated(Throwable e, String constraintName) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && constraintName.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.contains("\"" + constraintName + "\"")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}

package com.ospaulos.vupth.domain.attendance.exception;

public class AttendanceServiceItemNotFoundException extends RuntimeException {
    public AttendanceServiceItemNotFoundException() {
        super("No service items found for attendance");
    }
}

package com.openvpp.assessment.predict;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * 三态日历 —— 专栏第 47 篇特征工程的日期类型：0 工作日/1 周末/2 法定节假日。
 *
 * 已知债务（生产注释原话的教学转述）：节假日表是硬编码的，每年国务院
 * 公布放假安排后需要人工同步 Python 与 Java 两份表——双重维护无自动校验，
 * 这是跨语言契约五处手工同步点之一。
 */
public class PredictCalendar {

    private final Set<LocalDate> holidays;

    /** 无节假日表：仅按自然周末判定（未公布年份不写死调休）。 */
    public PredictCalendar() {
        this(java.util.Collections.emptySet());
    }

    public PredictCalendar(Set<LocalDate> holidays) {
        this.holidays = holidays;
    }

    /** 0 工作日 / 1 周末 / 2 法定节假日。 */
    public int dateType(LocalDate date) {
        if (holidays.contains(date)) {
            return 2;
        }
        DayOfWeek dow = date.getDayOfWeek();
        return (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) ? 1 : 0;
    }
}

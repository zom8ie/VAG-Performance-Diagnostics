package ru.yeti.diag;

/** Keeps a successfully decoded value visible across a temporary read failure. */
final class MeasurementRetention {
    private MeasurementRetention(){}

    static Engine.Row keepLastSuccessful(Engine.Row previous,Engine.Row incoming){
        if(previous!=null&&hasSuccessfulValue(previous.value)&&isTemporaryMissing(incoming))return previous;
        return incoming;
    }

    static boolean hasSuccessfulValue(String value){
        return !value.equals("—")&&!value.equals("Недоступно")&&!value.equals("Ответ сохранён");
    }

    static boolean isTemporaryMissing(Engine.Row row){
        return row.value.equals("—")&&row.note.equals("Нет данных / неполный ответ")
            ||row.value.equals("Недоступно")&&row.note.startsWith("Нет положительного ответа")
            ||row.value.equals("Ответ сохранён")&&row.note.contains("формат требует сверки");
    }
}

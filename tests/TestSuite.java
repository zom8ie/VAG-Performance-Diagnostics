package ru.yeti.diag;

public final class TestSuite {
    public static void main(String[] args)throws Exception{
        CanProtocolTest.run();
        MeasurementTest.run();
        ReadOnlyCommandsTest.run();
        TransportTest.run();
        DiagnosticBlocksTest.run();
        LoggedReadingsTest.run();
        PollingReadTest.run();
        DashboardTest.run();
        PollingBenchmarkTest.run();
        System.out.println("PASS: "+TestChecks.checks+" checks across 9 suites");
    }
}

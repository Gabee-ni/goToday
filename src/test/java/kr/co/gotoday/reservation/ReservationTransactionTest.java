package kr.co.gotoday.reservation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import kr.co.gotoday.payment.PaymentMapper;
import kr.co.gotoday.payment.PaymentVO;
import kr.co.gotoday.user.CalendarVO;

/** Uses the real Spring proxy and production MyBatis XML against an isolated H2 DB. */
class ReservationTransactionTest {
    private AnnotationConfigApplicationContext context;
    private ReservationService service;
    private PaymentMapper payments;
    private ReservationMapper reservations;
    private TossPaymentClient toss;
    private JdbcTemplate jdbc;
    private CountDownLatch calendarFinished;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        service = context.getBean(ReservationService.class);
        payments = context.getBean(PaymentMapper.class);
        reservations = context.getBean(ReservationMapper.class);
        toss = context.getBean(TossPaymentClient.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));

        calendarFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            calendarFinished.countDown();
            return 1;
        }).when(reservations).createScheduleByReservation(any(CalendarVO.class));
        when(toss.confirmPayment("test-payment", "test-order", 2000)).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "The paid flow must not hold a DB transaction during the external API call");
            assertEquals(8, tickets());
            return payment();
        });
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void paidSuccessCommitsBothRowsAndLinksGeneratedReservationId() throws Exception {
        ReservationVO saved = confirmPaid();
        assertEquals(1, count("reservation"));
        assertEquals(1, count("payment"));
        assertEquals(8, tickets());
        assertTrue(saved.getReservation_id() > 0);
        assertEquals(saved.getReservation_id(), jdbc.queryForObject(
                "SELECT reservation_id FROM payment", Integer.class).intValue());
        assertEquals("DONE", jdbc.queryForObject("SELECT reservation_status FROM reservation", String.class));
        assertTrue(calendarFinished.await(5, TimeUnit.SECONDS));
        verify(toss, never()).cancelPayment(anyString(), anyString());
    }

    @Test
    void paymentSqlFailureRollsBackReservationAndCompensatesPaidBooking() {
        PaymentVO invalid = payment();
        invalid.setPayment_method(null); // Real NOT NULL violation after the reservation INSERT.
        doReturn(invalid).when(toss).confirmPayment("test-payment", "test-order", 2000);

        assertThrows(RuntimeException.class, this::confirmPaid);

        assertNoBookingAndRestoredStock();
        verify(toss).cancelPayment(eq("test-payment"), anyString());
        verify(reservations, never()).createScheduleByReservation(any());
    }

    @Test
    void zeroPaymentRowsRaisesCheckedExceptionAndStillRollsBackReservation() {
        doReturn(0).when(payments).createPayment(any());

        RuntimeException failure = assertThrows(RuntimeException.class, this::confirmPaid);

        assertEquals("결제 정보 저장에 실패했습니다.", failure.getCause().getMessage());
        assertNoBookingAndRestoredStock();
        verify(toss).cancelPayment(eq("test-payment"), anyString());
    }

    @Test
    void approvalFailureRestoresStockWithoutCreatingBookingOrCallingCancel() {
        doThrow(new RuntimeException("simulated approval rejection"))
                .when(toss).confirmPayment("test-payment", "test-order", 2000);

        assertThrows(RuntimeException.class, this::confirmPaid);

        assertNoBookingAndRestoredStock();
        verify(toss, never()).cancelPayment(anyString(), anyString());
        verify(payments, never()).createPayment(any());
    }

    @Test
    void insufficientTicketsDoesNotCallTossOrIncreaseStock() {
        jdbc.update("UPDATE content_schedule SET current_ticket = 1");

        assertThrows(RuntimeException.class, this::confirmPaid);

        assertEquals(1, tickets());
        assertEquals(0, count("reservation"));
        assertEquals(0, count("payment"));
        verifyNoInteractions(toss);
    }

    @Test
    void freeFailureRollsBackOuterTransactionWithoutDoubleRestoringStock() {
        doReturn(0).when(payments).createPayment(any());
        ReservationVO reservation = reservation();
        reservation.setTotal_price(0);

        assertThrows(RuntimeException.class,
                () -> service.confirmFreeReservation(reservation, "free-order"));

        assertNoBookingAndRestoredStock();
        verifyNoInteractions(toss);
        verify(reservations, never()).createScheduleByReservation(any());
    }

    @Test
    void freeSuccessCommitsBothRowsWithoutCallingToss() throws Exception {
        ReservationVO reservation = reservation();
        reservation.setTotal_price(0);

        service.confirmFreeReservation(reservation, "free-order");

        assertEquals(1, count("reservation"));
        assertEquals(1, count("payment"));
        assertEquals(8, tickets());
        assertEquals("FREE", jdbc.queryForObject("SELECT payment_method FROM payment", String.class));
        assertTrue(calendarFinished.await(5, TimeUnit.SECONDS));
        verifyNoInteractions(toss);
    }

    @Test
    void compensationFailureDoesNotPreventDatabaseRollbackOrStockRestore() {
        doReturn(0).when(payments).createPayment(any());
        doThrow(new RuntimeException("simulated cancellation failure"))
                .when(toss).cancelPayment(anyString(), anyString());

        assertThrows(RuntimeException.class, this::confirmPaid);

        assertNoBookingAndRestoredStock();
        verify(toss).cancelPayment(eq("test-payment"), anyString());
    }

    private ReservationVO confirmPaid() {
        return service.confirmAndCreateReservation(reservation(), "test-payment", "test-order", 2000);
    }

    private ReservationVO reservation() {
        ReservationVO value = new ReservationVO();
        value.setReservation_code("test-reservation");
        value.setReservation_status("PENDING");
        value.setSchedule_id(1);
        value.setAdult_qty(2);
        value.setTotal_price(2000);
        value.setUser_id(1);
        value.setContent_id(1);
        value.setReserved_for_at("2026-12-01");
        value.setTime_zone("10:00");
        return value;
    }

    private PaymentVO payment() {
        PaymentVO value = new PaymentVO();
        value.setPayment_key("test-payment");
        value.setOrder_key("test-order");
        value.setPayment_method("CARD");
        value.setPayment_status("DONE");
        value.setAmount_price(2000);
        value.setRefund_status("NONE");
        return value;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private int tickets() {
        return jdbc.queryForObject("SELECT current_ticket FROM content_schedule WHERE schedule_id = 1", Integer.class);
    }

    private void assertNoBookingAndRestoredStock() {
        assertEquals(0, count("reservation"));
        assertEquals(0, count("payment"));
        assertEquals(10, tickets());
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            new ResourceDatabasePopulator(new ClassPathResource("reservation-transaction-schema.sql")).execute(source);
            return source;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        SqlSessionTemplate sqlSession(DataSource source) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(
                    new ClassPathResource("kr/co/gotoday/reservation/ReservationMapper.xml"),
                    new ClassPathResource("kr/co/gotoday/payment/PaymentMapper.xml"));
            return new SqlSessionTemplate(factory.getObject());
        }

        @Bean
        ReservationMapper reservationMapper(SqlSessionTemplate session) {
            return mock(ReservationMapper.class, delegatesTo(session.getMapper(ReservationMapper.class)));
        }

        @Bean
        PaymentMapper paymentMapper(SqlSessionTemplate session) {
            return mock(PaymentMapper.class, delegatesTo(session.getMapper(PaymentMapper.class)));
        }

        @Bean
        TossPaymentClient tossPaymentClient() {
            return mock(TossPaymentClient.class);
        }

        @Bean
        ReservationPaymentService reservationPaymentService(ReservationMapper reservations, PaymentMapper payments) {
            return new ReservationPaymentService(reservations, payments);
        }

        @Bean
        ReservationService reservationService() {
            return new ReservationServiceImpl();
        }
    }
}

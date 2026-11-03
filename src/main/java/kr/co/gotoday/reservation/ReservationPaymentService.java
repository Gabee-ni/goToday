package kr.co.gotoday.reservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.gotoday.payment.PaymentMapper;
import kr.co.gotoday.payment.PaymentVO;

/** 예약과 결제 내역의 DB 저장 경계. 외부 결제 API는 호출하지 않는다. */
@Service
public class ReservationPaymentService {

    private final ReservationMapper reservationMapper;
    private final PaymentMapper paymentMapper;

    public ReservationPaymentService(ReservationMapper reservationMapper, PaymentMapper paymentMapper) {
        this.reservationMapper = reservationMapper;
        this.paymentMapper = paymentMapper;
    }

    // 무료 예약에서는 기존 트랜잭션에 참여하고, 유료 예약에서는 새 트랜잭션을 시작한다.
    @Transactional(rollbackFor = Exception.class)
    public ReservationVO saveReservationWithPayment(ReservationVO reservation, PaymentVO payment) throws Exception {
        reservation.setReservation_status("DONE");
        if (reservationMapper.createReservation(reservation) <= 0) {
            throw new Exception("예약 정보 저장에 실패했습니다.");
        }

        payment.setReservation_id(reservation.getReservation_id());
        if (paymentMapper.createPayment(payment) <= 0) {
            throw new Exception("결제 정보 저장에 실패했습니다.");
        }
        return reservation;
    }
}

package kr.co.gotoday.reservation;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import kr.co.gotoday.content.ContentScheduleVO;
import kr.co.gotoday.content.ContentVO;
import kr.co.gotoday.payment.PaymentVO;
import kr.co.gotoday.user.CalendarVO;
import util.PageInfo;

@Service
public class ReservationServiceImpl implements ReservationService{
	@Autowired
	ReservationMapper reservationMapper;
	@Autowired
	ReservationPaymentService reservationPaymentService;
	@Autowired
	TossPaymentClient tossPaymentClient;
	
	private static final Logger log =
	        LoggerFactory.getLogger(ReservationServiceImpl.class);
	

	@Override
	public int calculate(ReservationDTO reservationDTO, ContentVO contentVO) {
		if (reservationDTO == null || contentVO == null) {
            throw new IllegalArgumentException("예약 정보와 콘텐츠 정보 누락");
        }
		
		int adultPrice = reservationDTO.getAdult_qty() * contentVO.getAdult_price();
		int teenPrice = reservationDTO.getTeen_qty() * contentVO.getTeen_price();
		int childPrice = reservationDTO.getChild_qty() * contentVO.getChild_price();
		
		return adultPrice + teenPrice + childPrice;
	}
	
	@Override
	public ReservationVO convertToVO(ReservationDTO dto, ReservationVO reservationVO) {
		// 시간정보
		reservationVO.setReserved_for_at(dto.getReserved_for_at());
		reservationVO.setTime_zone(dto.getTime_zone());
		reservationVO.setSchedule_id(dto.getSchedule_id());
		
		// 수량 및 금액 정보
		reservationVO.setAdult_qty(dto.getAdult_qty());
		reservationVO.setTeen_qty(dto.getTeen_qty());
		reservationVO.setChild_qty(dto.getChild_qty());
		reservationVO.setTotal_price(dto.getTotal_price());
		
		// 콘텐츠 정보
		reservationVO.setContent_id(dto.getContent_id());
		
		// 임시 예약 생성
		reservationVO.setReservation_code("RES_" + System.currentTimeMillis());
		reservationVO.setReservation_status("PENDING");
		
		return reservationVO;
	}
	
	@Override
	public ReservationVO findByReservationId(int reservation_id) {
		return reservationMapper.findByReservationId(reservation_id);
	}
	
	@Override
	@Transactional
	public ReservationVO confirmFreeReservation(ReservationVO reservationVO, String order_key){
		//사전에 중복 결제 방지 
		PaymentVO existingPayment = reservationMapper.findByOrderId(order_key);
	    if (existingPayment != null) {
	        throw new RuntimeException("DUPLICATE:이미 처리된 주문입니다.");
	    }
	    return confirmAndCreateReservation(reservationVO, null, order_key, 0);
	}
	
	@Override
	public ContentScheduleVO findCurrentTickets(int schedule_id) {
		return reservationMapper.findCurrentTickets(schedule_id);
	}
	
	@Override
	public ReservationVO confirmAndCreateReservation(ReservationVO reservationVO, String paymentKey, String orderId, int amount) {
		PaymentVO paymentVO = null;
		boolean ticketSucceed = false; 
		try {
			
			//confirm 중복 호출로 인한 결제 내역 중복 저장 방지
			PaymentVO existToss = reservationMapper.findByPaymentKey(paymentKey);
			if (existToss != null) {
			    log.warn("[DUPLICATE_CONFIRM] paymentKey={}, orderId={}", paymentKey, orderId);

			    ReservationVO existReservation =
			        reservationMapper.findByReservationId(existToss.getReservation_id());

			    
			    return existReservation;
			}
			
			// 잔여 확인 후 티켓 선차감 (중복 결제 방지)
			trySubCurrentTicket(reservationVO);
			ticketSucceed = true;
			
			// 유, 무료에 의해 분기됨
			if (amount == 0) {
	            //무료 결제 로직
	            paymentVO = new PaymentVO();
	            paymentVO.setOrder_key(orderId);
	            paymentVO.setAmount_price(0);
	            paymentVO.setPayment_method("FREE");
	            paymentVO.setRefund_status("NONE");
	            paymentVO.setPayment_status("DONE");
	            
	        } else {
	            //유료 결제 로직
	            paymentVO = tossPaymentClient.confirmPayment(
	                paymentKey, orderId, amount
	            );
	        }
			// 별도 Spring 빈의 프록시를 통해 예약 + 결제 저장 트랜잭션 실행
			ReservationVO savedReservation = 
		            createReservationWithPaymentent(reservationVO, paymentVO);
			
			// 캘린더 저장 (비동기)
			CompletableFuture.runAsync(() -> {
				try {
					createScheduleByReservation(savedReservation);
					log.info(
						"[CALENDAR_SUCCESS] reservationId={}, userId={}", 
						savedReservation.getReservation_id(),
						savedReservation.getUser_id()
					);
				} catch (Exception e) {
					//로그만 남기고 별도 에러 던지지는 않음 -> 캘린더 저장 실패했다고 토스 결제 취소하면 X 되기 때문
					log.error(
						"[CALENDAR_FAILED] reservationId={}, userId={}",
						savedReservation.getReservation_id(),
						savedReservation.getUser_id()
					);
				}
			});
			
			return savedReservation;
			
		} catch (Exception e) {
			//토스 결제 이후 에러가 발생하여 예약은 안 된 경우, 
			if (paymentVO != null && paymentVO.getAmount_price()!=0) {
				try {
					//결제 승인 후 DB저장 실패 로그
					log.error("[예약결제 DB저장 실패]",e);
					// 토스 결제 취소
					tossPaymentClient.cancelPayment(paymentKey, "DB 저장 실패로 인한 토스 결제 취소");
				} catch (Exception cancelException) {
					log.error("[토스 결제 취소 실패] paymentKey={}, orderId={}", 
							paymentKey,
							orderId
							);
				}
			}
			//티켓 선차감 했는데 에러가 난 경우 -> 티켓 복구 
			if (ticketSucceed) {
				try {
					tryAddCurrentTicket(reservationVO);
				} catch (Exception cancelException) {
					log.error("[티켓 복구 실패] scheduleId={},total_qty={}", 
							reservationVO.getSchedule_id(),
							(reservationVO.getAdult_qty() +reservationVO.getTeen_qty() +reservationVO.getChild_qty())
							);
				}
			}
			throw new RuntimeException("예약 처리 중 오류 발생: " + e.getMessage(), e);
		}
	}
	
	@Override
	@Transactional
	public int trySubCurrentTicket(ReservationVO reservationVO) throws Exception {
		
		int total_qty = reservationVO.getAdult_qty()
				+reservationVO.getTeen_qty()
				+ reservationVO.getChild_qty();
		
		Map< String, Object> map = new HashMap<String, Object>();
		map.put("total_qty", total_qty);
		map.put("schedule_id", reservationVO.getSchedule_id());
		
		int result = reservationMapper.subCurrentTicket(map);
		
		if (result < 1) {
			log.error(
		            "[TICKET_SUB_FAILED] scheduleId={}, requestQty={}",
		            reservationVO.getSchedule_id(),
		            total_qty
					);
			throw new Exception("티켓이 모두 소진되어 결제가 진행되지 않았습니다.\r\n"
					+ "결제 금액은 청구되지 않습니다.");
		}
		
		return result;
	}
	
	@Override
	@Transactional
	public int tryAddCurrentTicket(ReservationVO reservationVO) throws Exception {
	    int total_qty = reservationVO.getAdult_qty()
	            + reservationVO.getTeen_qty()
	            + reservationVO.getChild_qty();
	    
	    Map<String, Object> map = new HashMap<>();
	    map.put("total_qty", total_qty);
	    map.put("schedule_id", reservationVO.getSchedule_id());
	    
	    int result = reservationMapper.addCurrentTicket(map);
	    
	    if (result < 1) {
	        log.error(
	            "[TICKET_ADD_FAILED] scheduleId={}, qty={} - 복구 후 total_ticket 초과 가능성",
	            reservationVO.getSchedule_id(),
	            total_qty
	        );
	        throw new Exception("티켓 복구에 실패했습니다. (최대 티켓 수 초과)");
	    }
	    
	    return result;
	}

	@Override
	public ReservationVO createReservationWithPaymentent(ReservationVO reservationVO, PaymentVO paymentVO) throws Exception {
		return reservationPaymentService.saveReservationWithPayment(reservationVO, paymentVO);
	}

	
	@Override
	public void createScheduleByReservation(ReservationVO reservationVO){
		
		String selectedAt = reservationVO.getReserved_for_at()+" "+reservationVO.getTime_zone();
		
		CalendarVO calendarVO = new CalendarVO();
		calendarVO.setSelected_at(selectedAt);
		calendarVO.setContent_id(reservationVO.getContent_id());
		calendarVO.setType("reserve");
		calendarVO.setUser_id(reservationVO.getUser_id());
		calendarVO.setReservation_id(reservationVO.getReservation_id());
		
		reservationMapper.createScheduleByReservation(calendarVO);
	}

	@Override
	public void updatePaymentStatus(String order_key) {
		try {
			PaymentVO payment = reservationMapper.findByOrderId(order_key);
			
			if (payment == null || "DONE".equals(payment.getPayment_status())) {
				return; // 결제 정보 없거나 이미 완료면 스킵
			}
			
			Map<String, Object> map = new HashMap<>();
			map.put("order_key", order_key);
			map.put("payment_status", "DONE");
			
			reservationMapper.updatePaymentStatus(map);
			
		} catch (Exception e) {
			log.error("[결제 상태 업데이트 실패] order_key={}", order_key, e);
		}
		
	}

	@Override
	public int updateReservationStatusById(int reservation_id) {
		return reservationMapper.updateReservationStatusById(reservation_id);
	}

	@Override
	public List<ReservationListDTO> findReservationListByUserId(int user_id, String filter) {
		Map<String, Object> map = new HashMap<>();
		map.put("user_id", user_id);
		map.put("filter", filter);
		
		List<ReservationListDTO> listDTO = reservationMapper.findReservationListByUserId(map);
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		
		for(ReservationListDTO dto : listDTO) {
			if ("CANCELED".equals(dto.getReservation_status())) {
	            dto.setDday("CANCELED");
	            continue;
	        }
				
			long diff = ChronoUnit.DAYS.between(today, dto.getReserved_for_at());
			
			if (diff > 0) dto.setDday("D-" + diff);
			else if (diff == 0) dto.setDday("D-Day");
			else dto.setDday("END");
		}
		
		listDTO.sort((a,b)->{
			boolean aEnd = "END".equals(a.getDday()) || "CANCELED".equals(a.getDday());
			boolean bEnd = "END".equals(b.getDday()) || "CANCELED".equals(b.getDday());

			//END 아닌 거가 먼저 오게
			if (aEnd && !bEnd) return 1;
			if (!aEnd && bEnd) return -1;
			//둘 다 END면 최근으로 정렬
			if (aEnd && bEnd) {
	            return b.getReserved_for_at().compareTo(a.getReserved_for_at());
	        }
			//둘 다 진행중이면 가까운 날짜순
	        return a.getReserved_for_at().compareTo(b.getReserved_for_at());
		});

		return listDTO;
	}

	@Override
	public Map<String, Object> findReservationListByUserId(int user_id, String filter, Integer page, String keyword) {
		final int PAGE_SIZE = 10;
		final int BLOCK_SIZE = 5;

		Map<String, Object> param = new HashMap<>();
		param.put("user_id", user_id);
		param.put("filter", filter);
		if (keyword != null && !keyword.trim().isEmpty()) {
			param.put("keyword", keyword.trim());
		}

		// COUNT 조회
		int count = reservationMapper.countReservationByUserId(param);

		// 페이징 정보 생성
		PageInfo pageInfo = PageInfo.of(count, page, PAGE_SIZE, BLOCK_SIZE);

		// offset, pageSize 추가
		param.put("offset", PageInfo.offset(page, PAGE_SIZE));
		param.put("pageSize", PAGE_SIZE);

		// 목록 조회
		List<ReservationListDTO> listDTO = reservationMapper.findReservationListByUserId(param);
		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

		for (ReservationListDTO dto : listDTO) {
			if ("CANCELED".equals(dto.getReservation_status())) {
				dto.setDday("CANCELED");
				continue;
			}

			long diff = ChronoUnit.DAYS.between(today, dto.getReserved_for_at());

			if (diff > 0) dto.setDday("D-" + diff);
			else if (diff == 0) dto.setDday("D-Day");
			else dto.setDday("END");
		}

		Map<String, Object> result = new HashMap<>();
		result.put("list", listDTO);
		result.put("pageInfo", pageInfo);
		return result;
	}

	@Override
	public ReservationDetailDTO findReservationDetailById(int reservation_id, int user_id) {
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("reservation_id", reservation_id);
		map.put("user_id", user_id);

		ReservationDetailDTO dto = reservationMapper.findReservationDetailById(map);

		if (dto == null) {
			throw new RuntimeException("예약 정보를 찾을 수 없습니다. reservation_id=" + reservation_id);
		}

    	String birth = dto.getReceiver_birth();
    	if (birth != null && birth.length() >= 10) {
    	    birth = birth.substring(0, 10); //yyyy-MM-dd
    	}
    	dto.setReceiver_birth(birth);
    	
    	String hp = dto.getReceiver_phone();
    	dto.setReceiver_phone(formatPhone(hp));
		
		return dto;
	}
	
	//별도의 휴대폰번호 파싱 메서드
	public String formatPhone(String phone) {
	    if (phone == null) return "";

	    // 숫자만 남기기 (혹시 - 들어온 경우 대비)
	    phone = phone.replaceAll("[^0-9]", "");

	    // 010xxxxxxxx (11자리)만 포맷
	    if (phone.length() == 11) {
	        return phone.replaceFirst(
	            "(\\d{3})(\\d{4})(\\d{4})",
	            "$1-$2-$3"
	        );
	    }

	    // 집 전화의 경우
	    if (phone.length() == 10) {
	        return phone.replaceFirst(
	            "(\\d{3})(\\d{3})(\\d{4})",
	            "$1-$2-$3"
	        );
	    }

	    // 그 외는 원본 그대로
	    return phone;
	}

	
}

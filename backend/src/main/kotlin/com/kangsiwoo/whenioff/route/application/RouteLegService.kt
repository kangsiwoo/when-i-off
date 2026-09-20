package com.kangsiwoo.whenioff.route.application

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.common.api.NotFoundException
import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.route.api.CommuteRouteDetailResponse
import com.kangsiwoo.whenioff.route.api.RouteLegRequest
import com.kangsiwoo.whenioff.route.api.RouteLegResponse
import com.kangsiwoo.whenioff.route.api.SignalCrossingRequest
import com.kangsiwoo.whenioff.route.domain.CommuteRoute
import com.kangsiwoo.whenioff.route.domain.LegType
import com.kangsiwoo.whenioff.route.domain.RouteLeg
import com.kangsiwoo.whenioff.route.domain.RouteLegRepository
import com.kangsiwoo.whenioff.route.domain.RouteLegSignalCrossing
import com.kangsiwoo.whenioff.route.domain.RouteLegSignalCrossingRepository
import com.kangsiwoo.whenioff.signal.domain.SignalCodes
import com.kangsiwoo.whenioff.signal.domain.TrafficSignal
import com.kangsiwoo.whenioff.signal.domain.TrafficSignalRepository
import com.kangsiwoo.whenioff.transit.domain.TransitLine
import com.kangsiwoo.whenioff.transit.domain.TransitLineRepository
import com.kangsiwoo.whenioff.transit.domain.TransitStop
import com.kangsiwoo.whenioff.transit.domain.TransitStopRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class RouteLegService(
    private val commuteRouteService: CommuteRouteService,
    private val routeLegRepository: RouteLegRepository,
    private val signalCrossingRepository: RouteLegSignalCrossingRepository,
    private val transitLineRepository: TransitLineRepository,
    private val transitStopRepository: TransitStopRepository,
    private val trafficSignalRepository: TrafficSignalRepository,
) {
    fun replaceLegs(
        routeId: Long,
        requests: List<RouteLegRequest>,
    ): CommuteRouteDetailResponse {
        val route = commuteRouteService.findOwned(routeId)
        val ordered = requests.sortedBy { it.seqOrder }
        validateSequence(ordered)

        val lines = mutableMapOf<Long, TransitLine>()
        val stops = mutableMapOf<Long, TransitStop>()
        val legs =
            ordered.map { req ->
                when (req.legType) {
                    LegType.WALK -> walkLeg(route, req)
                    LegType.TRANSIT -> transitLeg(route, req, lines, stops)
                }
            }

        // 기존 구간과 seq_order UNIQUE가 겹치므로 삭제를 먼저 DB에 반영한 뒤 삽입한다.
        routeLegRepository.deleteAllInBatch(routeLegRepository.findByCommuteRouteOrderBySeqOrderAsc(route))
        routeLegRepository.saveAllAndFlush(legs)
        return commuteRouteService.toDetail(route)
    }

    fun replaceSignalCrossings(
        legId: Long,
        requests: List<SignalCrossingRequest>,
    ): RouteLegResponse {
        val leg = findOwnedLeg(legId)
        if (leg.legType != LegType.WALK) throw BadRequestException("signal crossings can only be set on a WALK leg")
        val ordered = requests.sortedBy { it.seqOrder }
        requireContiguous(ordered.map { it.seqOrder }, "crossings")

        val signals = mutableMapOf<Long, TrafficSignal>()
        val crossings =
            ordered.map { req ->
                if (req.approachDir !in SignalCodes.APPROACH_DIRS) {
                    throw BadRequestException(
                        "crossing ${req.seqOrder}: approachDir must be one of ${SignalCodes.APPROACH_DIRS}",
                    )
                }
                if (req.signalKind !in SignalCodes.SIGNAL_KINDS) {
                    throw BadRequestException(
                        "crossing ${req.seqOrder}: signalKind must be one of ${SignalCodes.SIGNAL_KINDS}",
                    )
                }
                RouteLegSignalCrossing(
                    routeLeg = leg,
                    trafficSignal =
                        signals.getOrPut(req.trafficSignalId) {
                            trafficSignalRepository.findById(req.trafficSignalId).orElseThrow {
                                BadRequestException("traffic signal ${req.trafficSignalId} not found")
                            }
                        },
                    seqOrder = req.seqOrder,
                    approachDir = req.approachDir,
                    signalKind = req.signalKind,
                )
            }

        signalCrossingRepository.deleteAllInBatch(signalCrossingRepository.findByRouteLegOrderBySeqOrderAsc(leg))
        val saved = signalCrossingRepository.saveAllAndFlush(crossings)
        return RouteLegResponse.from(leg, saved)
    }

    private fun findOwnedLeg(legId: Long): RouteLeg {
        val leg = routeLegRepository.findById(legId).orElse(null)
        if (leg == null || leg.commuteRoute.user.id != DefaultUser.ID) {
            throw NotFoundException("route leg $legId not found")
        }
        return leg
    }

    private fun validateSequence(ordered: List<RouteLegRequest>) {
        requireContiguous(ordered.map { it.seqOrder }, "legs")
        if (ordered.first().legType != LegType.WALK || ordered.last().legType != LegType.WALK) {
            throw BadRequestException("legs must start and end with a WALK leg")
        }
        ordered.zipWithNext().forEach { (a, b) ->
            if (a.legType == b.legType) {
                throw BadRequestException("legs must alternate WALK/TRANSIT (seqOrder ${a.seqOrder} and ${b.seqOrder})")
            }
        }
    }

    private fun requireContiguous(
        seqOrders: List<Int>,
        what: String,
    ) {
        if (seqOrders != (1..seqOrders.size).toList()) {
            throw BadRequestException("$what seqOrder must be contiguous from 1, got $seqOrders")
        }
    }

    private fun walkLeg(
        route: CommuteRoute,
        req: RouteLegRequest,
    ): RouteLeg {
        val prefix = "leg ${req.seqOrder} (WALK)"
        if (req.startLat == null || req.startLng == null || req.endLat == null || req.endLng == null) {
            throw BadRequestException("$prefix: startLat/startLng/endLat/endLng are required")
        }
        if (req.transitLineId != null ||
            req.boardStopId != null ||
            req.alightStopId != null ||
            req.plannedTravelSec != null
        ) {
            throw BadRequestException("$prefix: transit fields must be empty")
        }
        return RouteLeg(
            commuteRoute = route,
            seqOrder = req.seqOrder,
            legType = LegType.WALK,
            startLat = req.startLat,
            startLng = req.startLng,
            endLat = req.endLat,
            endLng = req.endLng,
            plannedDistanceM = req.plannedDistanceM,
        )
    }

    private fun transitLeg(
        route: CommuteRoute,
        req: RouteLegRequest,
        lines: MutableMap<Long, TransitLine>,
        stops: MutableMap<Long, TransitStop>,
    ): RouteLeg {
        val prefix = "leg ${req.seqOrder} (TRANSIT)"
        if (req.transitLineId == null ||
            req.boardStopId == null ||
            req.alightStopId == null ||
            req.plannedTravelSec == null
        ) {
            throw BadRequestException("$prefix: transitLineId/boardStopId/alightStopId/plannedTravelSec are required")
        }
        if (req.startLat != null ||
            req.startLng != null ||
            req.endLat != null ||
            req.endLng != null ||
            req.plannedDistanceM != null
        ) {
            throw BadRequestException("$prefix: walk fields must be empty")
        }
        if (req.boardStopId == req.alightStopId) throw BadRequestException("$prefix: board and alight stop must differ")
        val line =
            lines.getOrPut(req.transitLineId) {
                transitLineRepository.findById(req.transitLineId).orElseThrow {
                    BadRequestException("$prefix: transit line ${req.transitLineId} not found")
                }
            }
        val board = stop(stops, req.boardStopId, prefix)
        val alight = stop(stops, req.alightStopId, prefix)
        if (board.mode != line.mode || alight.mode != line.mode) {
            throw BadRequestException("$prefix: stop mode must match the transit line mode ${line.mode}")
        }
        return RouteLeg(
            commuteRoute = route,
            seqOrder = req.seqOrder,
            legType = LegType.TRANSIT,
            transitLine = line,
            boardStop = board,
            alightStop = alight,
            plannedTravelSec = req.plannedTravelSec,
        )
    }

    private fun stop(
        cache: MutableMap<Long, TransitStop>,
        stopId: Long,
        prefix: String,
    ): TransitStop =
        cache.getOrPut(stopId) {
            transitStopRepository.findById(stopId).orElseThrow {
                BadRequestException("$prefix: transit stop $stopId not found")
            }
        }
}

package com.kangsiwoo.whenioff.route.application

import com.kangsiwoo.whenioff.common.api.BadRequestException
import com.kangsiwoo.whenioff.common.api.ConflictException
import com.kangsiwoo.whenioff.common.api.NotFoundException
import com.kangsiwoo.whenioff.common.auth.DefaultUser
import com.kangsiwoo.whenioff.route.api.CommuteRouteDetailResponse
import com.kangsiwoo.whenioff.route.api.RouteLegRequest
import com.kangsiwoo.whenioff.route.api.RouteLegResponse
import com.kangsiwoo.whenioff.route.api.SignalCrossingRequest
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

        val existing = routeLegRepository.findByCommuteRouteOrderBySeqOrderAsc(route)
        val matched = matchExisting(ordered, existing)
        val kept = matched.values.toSet()
        val removed = existing.filter { it !in kept }
        val retyped = ordered.mapNotNull { req -> matched[req.seqOrder]?.takeIf { it.legType != req.legType } }
        requireNoMeasurements(removed + retyped)

        // 재정렬 중 (commute_route_id, seq_order) UNIQUE와 겹치지 않도록 유지되는 구간을 먼저 음수 seq_order로 비켜 둔다.
        kept.forEach { it.seqOrder = -it.seqOrder }
        routeLegRepository.flush()
        routeLegRepository.deleteAllInBatch(removed)

        val lines = mutableMapOf<Long, TransitLine>()
        val stops = mutableMapOf<Long, TransitStop>()
        val legs =
            ordered.map { req ->
                val leg =
                    matched[req.seqOrder]
                        ?: RouteLeg(commuteRoute = route, seqOrder = req.seqOrder, legType = req.legType)
                leg.seqOrder = req.seqOrder
                leg.legType = req.legType
                when (req.legType) {
                    LegType.WALK -> applyWalk(leg, req)
                    LegType.TRANSIT -> applyTransit(leg, req, lines, stops)
                }
                leg
            }
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

    // id가 온 항목은 그 구간을, 없는 항목은 seqOrder·legType이 같은 남은 구간을 제자리에서 고친다.
    // 그래야 실측 기록이 붙은 route_leg_id가 구간 편집마다 바뀌지 않는다.
    private fun matchExisting(
        ordered: List<RouteLegRequest>,
        existing: List<RouteLeg>,
    ): Map<Int, RouteLeg> {
        val byId = existing.associateBy { it.id!! }
        val matched = mutableMapOf<Int, RouteLeg>()
        val taken = mutableSetOf<Long>()
        for (req in ordered) {
            val id = req.id ?: continue
            val leg = byId[id] ?: throw BadRequestException("leg ${req.seqOrder}: id $id is not a leg of this route")
            if (!taken.add(id)) throw BadRequestException("leg id $id appears more than once")
            matched[req.seqOrder] = leg
        }
        for (req in ordered) {
            if (req.id != null) continue
            val leg =
                existing.firstOrNull { it.id !in taken && it.seqOrder == req.seqOrder && it.legType == req.legType }
                    ?: continue
            taken += leg.id!!
            matched[req.seqOrder] = leg
        }
        return matched
    }

    private fun requireNoMeasurements(legs: List<RouteLeg>) {
        if (legs.isEmpty()) return
        val measured = routeLegRepository.findIdsWithMeasurements(legs.map { it.id!! }).sorted()
        if (measured.isNotEmpty()) {
            throw ConflictException(
                "legs $measured have recorded trips and cannot be removed or retyped; send them back with their id",
            )
        }
    }

    private fun applyWalk(
        leg: RouteLeg,
        req: RouteLegRequest,
    ) {
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
        leg.startLat = req.startLat
        leg.startLng = req.startLng
        leg.endLat = req.endLat
        leg.endLng = req.endLng
        leg.plannedDistanceM = req.plannedDistanceM
        leg.transitLine = null
        leg.boardStop = null
        leg.alightStop = null
        leg.plannedTravelSec = null
    }

    private fun applyTransit(
        leg: RouteLeg,
        req: RouteLegRequest,
        lines: MutableMap<Long, TransitLine>,
        stops: MutableMap<Long, TransitStop>,
    ) {
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
        leg.transitLine = line
        leg.boardStop = board
        leg.alightStop = alight
        leg.plannedTravelSec = req.plannedTravelSec
        leg.startLat = null
        leg.startLng = null
        leg.endLat = null
        leg.endLng = null
        leg.plannedDistanceM = null
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

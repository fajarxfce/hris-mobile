package dev.fajar.starter.leave.domain.entities

/** Independently versioned records; employment scope is retained with every balance. */
sealed interface LeaveReplicaRecord {
    data class Request(val value: LeaveRequestDetails) : LeaveReplicaRecord

    data class Balance(val employeeId: String, val value: LeaveBalance) : LeaveReplicaRecord
}

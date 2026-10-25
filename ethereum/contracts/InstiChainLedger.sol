// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

/// @title InstiChain institutional event ledger
/// @notice Ethereum counterpart of the Fabric InstiChainContract. It keeps the same
///         guarantees (ordered sequence numbers, idempotent request IDs, one revocation
///         per subject) but stores only a keccak256 hash of each canonical event
///         document. Full documents stay in the backend's PostgreSQL projection.
contract InstiChainLedger {
    struct Entry {
        uint64 sequence;
        uint64 blockNumber;
        uint64 committedAt;
        bytes32 eventType;
        bytes32 documentHash;
    }

    address public immutable owner;
    mapping(address => bool) public writers;
    uint64 public head;

    mapping(bytes16 => Entry) private entries;
    mapping(uint64 => bytes16) private sequenceToRequest;
    mapping(bytes16 => bytes16) private revocations;
    bytes16[] private revokedSubjects;
    /// Credential ID => issuing request ID. Credentials have their own IDs, so they
    /// are indexed at issuance to make them revocable.
    mapping(bytes16 => bytes16) private issuedBy;

    event EventAppended(
        bytes16 indexed requestId,
        uint64 indexed sequence,
        bytes32 eventType,
        bytes32 documentHash,
        bytes16 revokedSubject
    );
    event WriterUpdated(address indexed account, bool allowed);

    error NotOwner();
    error NotWriter();
    error InvalidRequest();
    error ConflictingEvent(bytes16 requestId);
    error AlreadyRevoked(bytes16 subjectId);
    error UnknownSubject(bytes16 subjectId);
    error SubjectAlreadyRecorded(bytes16 subjectId);

    constructor() {
        owner = msg.sender;
        writers[msg.sender] = true;
        emit WriterUpdated(msg.sender, true);
    }

    modifier onlyOwner() {
        if (msg.sender != owner) revert NotOwner();
        _;
    }

    modifier onlyWriter() {
        if (!writers[msg.sender]) revert NotWriter();
        _;
    }

    /// @notice Allow or remove a backend account that may append events.
    function setWriter(address account, bool allowed) external onlyOwner {
        writers[account] = allowed;
        emit WriterUpdated(account, allowed);
    }

    /// @notice Append one institutional event. Re-sending an identical event is a
    ///         no-op that returns the original sequence; a different event under the
    ///         same request ID is rejected.
    /// @param revokedSubject Appointment or credential ID revoked by this event, or zero.
    /// @param issuedSubjects Credential IDs created by this event (may be empty).
    function appendEvent(
        bytes16 requestId,
        bytes32 eventType,
        bytes32 documentHash,
        bytes16 revokedSubject,
        bytes16[] calldata issuedSubjects
    ) external onlyWriter returns (uint64 sequence) {
        if (requestId == bytes16(0) || eventType == bytes32(0) || documentHash == bytes32(0)) {
            revert InvalidRequest();
        }
        Entry storage existing = entries[requestId];
        if (existing.sequence != 0) {
            if (existing.documentHash != documentHash || existing.eventType != eventType) {
                revert ConflictingEvent(requestId);
            }
            return existing.sequence;
        }
        if (revokedSubject != bytes16(0)) {
            if (revocations[revokedSubject] != bytes16(0)) revert AlreadyRevoked(revokedSubject);
            if (entries[revokedSubject].sequence == 0 && issuedBy[revokedSubject] == bytes16(0)) {
                revert UnknownSubject(revokedSubject);
            }
            revocations[revokedSubject] = requestId;
            revokedSubjects.push(revokedSubject);
        }
        for (uint256 index = 0; index < issuedSubjects.length; index++) {
            bytes16 subject = issuedSubjects[index];
            if (subject == bytes16(0)) revert InvalidRequest();
            if (issuedBy[subject] != bytes16(0) || entries[subject].sequence != 0) {
                revert SubjectAlreadyRecorded(subject);
            }
            issuedBy[subject] = requestId;
        }
        sequence = ++head;
        entries[requestId] = Entry(
            sequence, uint64(block.number), uint64(block.timestamp), eventType, documentHash);
        sequenceToRequest[sequence] = requestId;
        emit EventAppended(requestId, sequence, eventType, documentHash, revokedSubject);
    }

    function getEntry(bytes16 requestId) external view returns (
        bool exists,
        uint64 sequence,
        uint64 blockNumber,
        uint64 committedAt,
        bytes32 eventType,
        bytes32 documentHash
    ) {
        Entry storage entry = entries[requestId];
        return (entry.sequence != 0, entry.sequence, entry.blockNumber, entry.committedAt,
            entry.eventType, entry.documentHash);
    }

    function requestAt(uint64 sequence) external view returns (bytes16) {
        return sequenceToRequest[sequence];
    }

    function issuerOf(bytes16 subjectId) external view returns (bytes16) {
        return issuedBy[subjectId];
    }

    function revocationOf(bytes16 subjectId) external view returns (bytes16) {
        return revocations[subjectId];
    }

    function revokedSubjectCount() external view returns (uint256) {
        return revokedSubjects.length;
    }

    function revokedSubjectAt(uint256 index) external view returns (bytes16) {
        return revokedSubjects[index];
    }
}

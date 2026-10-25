const { expect } = require('chai')
const { ethers } = require('hardhat')

const id = (value) => ethers.zeroPadValue(ethers.toBeHex(value), 16)
const type = (value) => ethers.encodeBytes32String(value)
const hash = (value) => ethers.keccak256(ethers.toUtf8Bytes(value))
const NONE = '0x' + '00'.repeat(16)

describe('InstiChainLedger', () => {
  async function deploy() {
    const [owner, other] = await ethers.getSigners()
    const ledger = await ethers.deployContract('InstiChainLedger')
    return { ledger, owner, other }
  }

  it('assigns increasing sequence numbers and records metadata', async () => {
    const { ledger } = await deploy()
    await expect(ledger.appendEvent(id(1), type('CLUB_CREATED'), hash('a'), NONE, []))
      .to.emit(ledger, 'EventAppended').withArgs(id(1), 1, type('CLUB_CREATED'), hash('a'), NONE)
    await ledger.appendEvent(id(2), type('ADVISOR_APPOINTED'), hash('b'), NONE, [])

    expect(await ledger.head()).to.equal(2)
    expect(await ledger.requestAt(2)).to.equal(id(2))
    const stored = await ledger.getEntry(id(1))
    expect(stored.exists).to.equal(true)
    expect(stored.sequence).to.equal(1)
    expect(stored.documentHash).to.equal(hash('a'))
  })

  it('treats an identical retry as idempotent', async () => {
    const { ledger } = await deploy()
    await ledger.appendEvent(id(1), type('CLUB_CREATED'), hash('a'), NONE, [])
    await expect(ledger.appendEvent(id(1), type('CLUB_CREATED'), hash('a'), NONE, []))
      .not.to.emit(ledger, 'EventAppended')
    expect(await ledger.head()).to.equal(1)
  })

  it('rejects a different event under an existing request ID', async () => {
    const { ledger } = await deploy()
    await ledger.appendEvent(id(1), type('CLUB_CREATED'), hash('a'), NONE, [])
    await expect(ledger.appendEvent(id(1), type('CLUB_CREATED'), hash('changed'), NONE, []))
      .to.be.revertedWithCustomError(ledger, 'ConflictingEvent')
  })

  it('rejects empty identifiers', async () => {
    const { ledger } = await deploy()
    await expect(ledger.appendEvent(NONE, type('CLUB_CREATED'), hash('a'), NONE, []))
      .to.be.revertedWithCustomError(ledger, 'InvalidRequest')
  })

  it('allows only authorized writers and lets only the owner change them', async () => {
    const { ledger, other } = await deploy()
    await expect(ledger.connect(other).appendEvent(id(1), type('CLUB_CREATED'), hash('a'), NONE, []))
      .to.be.revertedWithCustomError(ledger, 'NotWriter')
    await expect(ledger.connect(other).setWriter(other.address, true))
      .to.be.revertedWithCustomError(ledger, 'NotOwner')
    await ledger.setWriter(other.address, true)
    await ledger.connect(other).appendEvent(id(1), type('CLUB_CREATED'), hash('a'), NONE, [])
    expect(await ledger.head()).to.equal(1)
  })

  it('revokes a subject once and indexes the revocation', async () => {
    const { ledger } = await deploy()
    await ledger.appendEvent(id(10), type('ROLE_GRANTED'), hash('grant'), NONE, [])
    await ledger.appendEvent(id(11), type('ROLE_REVOKED'), hash('revoke'), id(10), [])

    expect(await ledger.revocationOf(id(10))).to.equal(id(11))
    expect(await ledger.revokedSubjectCount()).to.equal(1)
    expect(await ledger.revokedSubjectAt(0)).to.equal(id(10))
    await expect(ledger.appendEvent(id(12), type('ROLE_REVOKED'), hash('again'), id(10), []))
      .to.be.revertedWithCustomError(ledger, 'AlreadyRevoked')
  })

  it('refuses to revoke a subject that was never recorded', async () => {
    const { ledger } = await deploy()
    await expect(ledger.appendEvent(id(11), type('ROLE_REVOKED'), hash('revoke'), id(99), []))
      .to.be.revertedWithCustomError(ledger, 'UnknownSubject')
  })

  it('revokes a credential registered by its issuing event', async () => {
    const { ledger } = await deploy()
    const credential = id(500)
    await expect(ledger.appendEvent(id(21), type('ACHIEVEMENT_REVOKED'), hash('early'), credential, []))
      .to.be.revertedWithCustomError(ledger, 'UnknownSubject')

    await ledger.appendEvent(id(20), type('ACHIEVEMENT_ISSUED'), hash('issue'), NONE, [credential, id(501)])
    expect(await ledger.issuerOf(credential)).to.equal(id(20))

    await ledger.appendEvent(id(21), type('ACHIEVEMENT_REVOKED'), hash('revoke'), credential, [])
    expect(await ledger.revocationOf(credential)).to.equal(id(21))
    await expect(ledger.appendEvent(id(22), type('ACHIEVEMENT_REVOKED'), hash('again'), credential, []))
      .to.be.revertedWithCustomError(ledger, 'AlreadyRevoked')
  })

  it('refuses to issue the same credential twice', async () => {
    const { ledger } = await deploy()
    await ledger.appendEvent(id(20), type('ACHIEVEMENT_ISSUED'), hash('issue'), NONE, [id(500)])
    await expect(ledger.appendEvent(id(23), type('ACHIEVEMENT_ISSUED'), hash('other'), NONE, [id(500)]))
      .to.be.revertedWithCustomError(ledger, 'SubjectAlreadyRecorded')
  })
})

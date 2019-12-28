package fr.happy.developer.domain.event

final case class LagWaitRegisteredEvent(lagWaitId: String) extends Event[String](lagWaitId)
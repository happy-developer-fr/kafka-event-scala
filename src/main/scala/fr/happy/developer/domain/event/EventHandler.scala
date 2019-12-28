package fr.happy.developer.domain.event

trait EventHandler[E] {
  def handle(event: E): EventHandleResult
}




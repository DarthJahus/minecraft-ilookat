package net.jahus.ilookat;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ILookAt implements ModInitializer {
	public static final String MOD_ID = "ilookat";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register(
			(dispatcher, registryAccess, environment) -> LookAtCommand.register(dispatcher)
		);
		LOGGER.info("I Look At loaded — /ilookat");
	}
}

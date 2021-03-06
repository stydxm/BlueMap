/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.core.resourcepack;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.commons.io.output.ByteArrayOutputStream;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resourcepack.BlockStateResource.Builder;
import de.bluecolored.bluemap.core.resourcepack.fileaccess.BluemapAssetOverrideFileAccess;
import de.bluecolored.bluemap.core.resourcepack.fileaccess.CaseInsensitiveFileAccess;
import de.bluecolored.bluemap.core.resourcepack.fileaccess.CombinedFileAccess;
import de.bluecolored.bluemap.core.resourcepack.fileaccess.FileAccess;
import de.bluecolored.bluemap.core.world.BlockState;

/**
 * Represents all resources (BlockStates / BlockModels and Textures) that are loaded and used to generate map-models. 
 */
public class ResourcePack {

	public static final String MINECRAFT_CLIENT_VERSION = "1.15.2";
	public static final String MINECRAFT_CLIENT_URL = "https://launcher.mojang.com/v1/objects/e3f78cd16f9eb9a52307ed96ebec64241cc5b32d/client.jar";
	
	private static final String[] CONFIG_FILES = {
		"blockColors.json",
		"blockIds.json",
		"blockProperties.json",
		"biomes.json"
	};
	
	protected Map<String, BlockStateResource> blockStateResources;
	protected Map<String, BlockModelResource> blockModelResources;
	protected TextureGallery textures;
	
	private BlockColorCalculator blockColorCalculator;
	
	private BufferedImage foliageMap;
	private BufferedImage grassMap;
	
	private Multimap<String, Resource> configs;
	
	public ResourcePack() {
		blockStateResources = new HashMap<>();
		blockModelResources = new HashMap<>();
		textures = new TextureGallery();
		foliageMap = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		foliageMap.setRGB(0, 0, 0xFF00FF00);
		grassMap = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		grassMap.setRGB(0, 0, 0xFF00FF00);
		blockColorCalculator = new BlockColorCalculator(foliageMap, grassMap);
		configs = MultimapBuilder.hashKeys().arrayListValues().build();
	}
	
	/**
	 * Returns all config-files found in the namespaces of the ResourcePack with that filename
	 */
	public Collection<Resource> getConfigAdditions(String configFileName){
		return configs.get(configFileName);
	}
	
	/**
	 * See {@link TextureGallery#loadTextureFile(File)}
	 * @see TextureGallery#loadTextureFile(File)
	 */
	public void loadTextureFile(File file) throws IOException, ParseResourceException {
		textures.loadTextureFile(file);
	}
	
	/**
	 * See {@link TextureGallery#saveTextureFile(File)}
	 * @see TextureGallery#saveTextureFile(File)
	 */
	public void saveTextureFile(File file) throws IOException {
		textures.saveTextureFile(file);
	}
	
	/**
	 * Loads and generates all {@link BlockStateResource}s from the listed sources.
	 * Resources from sources that are "later" (more to the end) in the list are overriding resources from sources "earlier" (more to the start/head) in the list.<br>
	 * <br>
	 * Any exceptions occurred while loading the resources are logged and ignored.
	 *  
	 * @param sources The list of {@link File} sources. Each can be a folder or any zip-compressed file. (E.g. .zip or .jar)
	 */
	public void load(Collection<File> sources) throws IOException {
		load(sources.toArray(new File[sources.size()]));
	}
	
	/**
	 * Loads and generates all {@link BlockStateResource}s and {@link Texture}s from the listed sources.
	 * Resources from sources that are "later" (more to the end) in the list are overriding resources from sources "earlier" (more to the start/head) in the list.<br>
	 * <br>
	 * Any exceptions occurred while loading the resources are logged and ignored.
	 *  
	 * @param sources The list of {@link File} sources. Each can be a folder or any zip-compressed file. (E.g. .zip or .jar)
	 */
	public void load(File... sources) {
		try (CombinedFileAccess combinedSources = new CombinedFileAccess()){
			for (File file : sources) {
				try {
					combinedSources.addFileAccess(FileAccess.of(file));
				} catch (IOException e) {
					Logger.global.logError("Failed to read ResourcePack: " + file, e);
				}
			}
			
			FileAccess sourcesAccess = new CaseInsensitiveFileAccess(new BluemapAssetOverrideFileAccess(combinedSources));
			
			textures.reloadAllTextures(sourcesAccess);
			
			Builder builder = BlockStateResource.builder(sourcesAccess, this);
			
			Collection<String> namespaces = sourcesAccess.listFolders("assets");
			for (String namespaceRoot : namespaces) {
				//load blockstates
				String namespace = namespaceRoot.substring("assets/".length());
				Collection<String> blockstateFiles = sourcesAccess.listFiles(namespaceRoot + "/blockstates", true);
				for (String blockstateFile : blockstateFiles) {
					String filename = FileAccess.getFileName(blockstateFile);
					if (!filename.endsWith(".json")) continue;

					try {
						blockStateResources.put(namespace + ":" + filename.substring(0, filename.length() - 5), builder.build(blockstateFile));
					} catch (IOException ex) {
						Logger.global.logError("Failed to load blockstate: " + namespace + ":" + filename.substring(0, filename.length() - 5), ex);
					}
				}
				
				//load configs
				for (String configName : CONFIG_FILES) {
					try {
						Resource config = new Resource(sourcesAccess.readFile("assets/" + namespace + "/" + configName));
						configs.put(configName, config);
					} catch (FileNotFoundException ignore) {
					} catch (IOException ex) {
						Logger.global.logError("Failed to load config for " + namespace + ": " + configName, ex);
					}
				}
			}
			
			try {
				foliageMap = ImageIO.read(sourcesAccess.readFile("assets/minecraft/textures/colormap/foliage.png"));
				blockColorCalculator.setFoliageMap(foliageMap);				
			} catch (IOException ex) {
				Logger.global.logError("Failed to load foliagemap!", ex);
			}

			try {
				grassMap = ImageIO.read(sourcesAccess.readFile("assets/minecraft/textures/colormap/grass.png"));
				blockColorCalculator.setGrassMap(grassMap);	
			} catch (IOException ex) {
				Logger.global.logError("Failed to load grassmap!", ex);
			}
			
		} catch (IOException ex) {
			Logger.global.logError("Failed to close FileAccess!", ex);
		}
	}
	
	/**
	 * Returns a {@link BlockStateResource} for the given {@link BlockState} if found. 
	 * @param state The {@link BlockState}
	 * @return The {@link BlockStateResource}
	 * @throws NoSuchResourceException If no resource is loaded for this {@link BlockState}
	 */
	public BlockStateResource getBlockStateResource(BlockState state) throws NoSuchResourceException {
		BlockStateResource resource = blockStateResources.get(state.getFullId());
		if (resource == null) throw new NoSuchResourceException("No resource for blockstate: " + state.getFullId());
		return resource;
	}
	
	public BlockColorCalculator getBlockColorCalculator() {
		return blockColorCalculator;
	}
	
	/**
	 * Synchronously downloads the default minecraft resources from the mojang-servers.
	 * @param file The file to save the downloaded resources to
	 * @throws IOException If an IOException occurs during the download
	 */
	public static void downloadDefaultResource(File file) throws IOException {
		if (file.exists()) file.delete();
		file.getParentFile().mkdirs();
		org.apache.commons.io.FileUtils.copyURLToFile(new URL(MINECRAFT_CLIENT_URL), file, 10000, 10000);
	}
	
	protected static String namespacedToAbsoluteResourcePath(String namespacedPath, String resourceTypeFolder) {
		String path = namespacedPath;
		
		resourceTypeFolder = FileAccess.normalize(resourceTypeFolder);
		
		int namespaceIndex = path.indexOf(':');
		String namespace = "minecraft";
		if (namespaceIndex != -1) {
			namespace = path.substring(0, namespaceIndex);
			path = path.substring(namespaceIndex + 1);
		}
		
		if (resourceTypeFolder.isEmpty()) {
			path = "assets/" + namespace + "/" + FileAccess.normalize(path);
		} else {
			path = "assets/" + namespace + "/" + resourceTypeFolder + "/" + FileAccess.normalize(path);			
		}
		
		return path;
	}
	
	/**
	 * Caches a full InputStream in a byte-array that can be read later
	 */
	public class Resource {
		
		private byte[] data;
		
		public Resource(InputStream data) throws FileNotFoundException, IOException {
			try (ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
				bout.write(data);
				this.data = bout.toByteArray();
			} finally {
				data.close();
			}
		}
		
		/**
		 * Creates a new InputStream to read this resource
		 */
		public InputStream read() {
			return new ByteArrayInputStream(this.data);
		}
		
	}
	
}

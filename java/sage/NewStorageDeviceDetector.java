/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage;

public class NewStorageDeviceDetector implements Runnable
{
  private static NewStorageDeviceDetector chosenOne;
  private static final Object chosenOneLock=new Object();

  public static NewStorageDeviceDetector getInstance() {
    if (chosenOne == null) {
      synchronized (chosenOneLock) {
        if (chosenOne == null) {
          chosenOne = new NewStorageDeviceDetector();
        }
      }
    }
    return chosenOne;
  }
  private NewStorageDeviceDetector()
  {
    deviceMap = new java.util.HashMap();
    mountMap = new java.util.HashMap();
    nameSerialMap = new java.util.Properties();

    if (Sage.EMBEDDED && Sage.LINUX_OS)
    {
      // Build the map so we know we have unique names for drives that are identical make/model/size
      serialProp = Sage.get("linux/usb_name_serial_map", "");
      java.util.StringTokenizer toker = new java.util.StringTokenizer(serialProp, "|");
      while (toker.hasMoreTokens())
      {
        String pair = toker.nextToken();
        int idx = pair.indexOf(",");
        if (idx != -1 && !"undefined".equals(pair.substring(0, idx)))
          nameSerialMap.setProperty(pair.substring(idx + 1), pair.substring(0, idx));
      }
      // Setup the USB device mappings now in case they're used for critical storage
      final String extDevRoot = Sage.EMBEDDED ? "/tmp" : SageTV.LINUX_ROOT_MEDIA_PATH;
      final java.util.Set mountedDevs = new java.util.HashSet();
      java.util.HashSet protectedDevices = new java.util.HashSet();
      String protectedStrs = Sage.get("linux/protected_devices", "");
      toker = new java.util.StringTokenizer(protectedStrs, ",;");
      while (toker.hasMoreTokens())
        protectedDevices.add(toker.nextToken());

      // Find any other protected devices (i.e. sata/scsi drives)
      java.io.File[] devFiles = new java.io.File("/sys/block").listFiles();
      for (int i = 0; devFiles != null && i < devFiles.length; i++)
      {
        String name = devFiles[i].getName();
        if (name.startsWith("sd") && name.length() > 2)
        {
          // Check the device path inside of here to see if it's a SATA internal drive
          try
          {
            if (new java.io.File(devFiles[i], "device").getCanonicalPath().indexOf("SATA") != -1 ||
                new java.io.File(devFiles[i], "device").getCanonicalPath().indexOf("ahci") != -1)
            {
              if (Sage.DBG) System.out.println("Found SATA HDD to ignore in external device mount checking of: " + name);
              protectedDevices.add(name);

              // Don't bother trying to mount the HDD ourselves anymore...this happens at the platform level
              if (false)
              {
                if (Sage.DBG) System.out.println("Mounting SATA HDD " + name + "1 at /var/media");
                IOUtils.mountExternalDrive(name + "1", "/var/media", true);
              }
            }
          }
          catch (java.io.IOException ioe)
          {
            if (Sage.DBG) System.out.println("Error resolving canon path: " + ioe);
          }
        }
      }
      if (Sage.getBoolean("enable_hotplug_storage_detector", true))
      {
        int devMapSizeStart = deviceMap.size();
        java.util.ArrayList seekerNotifies = new java.util.ArrayList();
        for (int i = 0; devFiles != null && i < devFiles.length; i++)
        {
          String name = devFiles[i].getName();
          if (protectedDevices.contains(name))
            continue;
          if (name.startsWith("sd") && name.length() > 2)
          {
            boolean foundParts = false;
            java.io.File[] subFiles = devFiles[i].listFiles();
            for (int j = 0; subFiles != null && j < subFiles.length; j++)
            {
              name = subFiles[j].getName();
              if (name.startsWith("sd") && name.length() > 3)
              {
                foundParts = true;
                mountLinuxDevice(name, extDevRoot, null, null, false, seekerNotifies);
              }
            }
            if (!foundParts)
            {
              // For NTFS there's no partitions, only the device, so try to mount just the device
              mountLinuxDevice(devFiles[i].getName(), extDevRoot, null, null, false, seekerNotifies);
            }
          }
        }
        for (int i = 0; i < seekerNotifies.size(); i++)
        {
          Object[] currInfo = (Object[]) seekerNotifies.get(i);
          if (!Sage.client && Seeker.getInstance().isAutoImportEnabled() && UIManager.getNonLocalUICount() == 0)
          {
            if (Sage.DBG) System.out.println("Automatically adding USB device to import path since no UI is available for prompting...");
            Seeker.getInstance().addArchiveDirectory(currInfo[0].toString(), Seeker.ALL_DIR_MASK);
          }
          Catbert.distributeHookToLocalUIs("StorageDeviceAdded", new Object[] { currInfo[0], currInfo[1] });
        }
      }
    }
  }

  // Keys are the 'pretty' names of the devices and the values are the actual mounted paths used to explore that device
  public java.util.Map getDeviceMap()
  {
    return deviceMap;
  }

  public void run()
  {
    if (Sage.DBG) System.out.println("StorageDeviceDetector started...");
    if (Sage.WINDOWS_OS)
    {
      windowsRun();
    }
    else if (Sage.LINUX_OS)
    {
      linuxRun();
    }
    else if (Sage.MAC_OS_X)
    {
      macRun();
    }
  }

  protected void windowsRun()
  {
    if (Sage.WINDOWS_OS && !Sage.isHeadless())
    {
      // Fix any network drive mappings that are lost....Windows should do this for us, but it doesn't
      // We do it in this thread since it may cause drives to temporarily disappear/reappear
      if (Sage.DBG) System.out.println("Starting fixing failed Windows mapped drives...");
      Sage.fixFailedWinDriveMappings();
      if (Sage.DBG) System.out.println("Done fixing failed Windows mapped drives.");
    }

    java.util.Set existingDrives = new java.util.HashSet(java.util.Arrays.asList(java.io.File.listRoots()));
    while (true)
    {
      java.util.Set currDrives = new java.util.HashSet(java.util.Arrays.asList(java.io.File.listRoots()));
      java.util.Set currDrivesClone = new java.util.HashSet(currDrives);
      currDrives.removeAll(existingDrives);

      java.util.Iterator walker = currDrives.iterator();
      while (walker.hasNext())
      {
        java.io.File newDrive = (java.io.File) walker.next();
        if (Sage.DBG) System.out.println("Detected new drive: " + newDrive);
        deviceMap.put(newDrive.toString(), newDrive);
        Catbert.distributeHookToLocalUIs("StorageDeviceAdded", new Object[] { newDrive, "" });
      }
      // If we lost any drives then run the library import scan again to clear out the temps
      existingDrives.removeAll(currDrivesClone);
      if (!existingDrives.isEmpty())
      {
        deviceMap.values().removeAll(existingDrives);
        if (Sage.DBG) System.out.println("Drives have been removed...rescan...");
        if (!Sage.client)
        {
          Seeker.getInstance().scanLibrary(false);
        }
      }
      existingDrives = currDrivesClone;
      try
      {
        Thread.sleep(Sage.getLong("linux/new_dev_scan_wait_period", 10000));
      }
      catch (Exception e)
      {}
    }
  }

  private void mountLinuxDevice(String name, String extDevRoot, java.util.Set mountedDevs,
      java.util.Set accountedDevs, boolean extraMountCheck, java.util.ArrayList seekerNotifies)
  {
    String specialName = "";
    String mountTarget = name;
    if (Sage.EMBEDDED)
    {
      java.io.BufferedReader fr = null;
      String devName = name;
      if (devName.length() > 3)
        devName = devName.substring(0, 3);
      try
      {
        fr = new java.io.BufferedReader(new java.io.FileReader("/sys/block/" + devName + "/device/vendor"));
        specialName += fr.readLine().trim() + " ";
      }
      catch (java.io.IOException e)
      {
        if (Sage.DBG) System.out.println("Error reading device vendor:" + e);
      }
      finally
      {
        if (fr != null)
        {
          try{fr.close();}catch(Exception e2){}
        }
      }
      try
      {
        fr = new java.io.BufferedReader(new java.io.FileReader("/sys/block/" + devName + "/device/model"));
        specialName += fr.readLine().trim() + " ";
      }
      catch (java.io.IOException e)
      {
        if (Sage.DBG) System.out.println("Error reading device model:" + e);
      }
      finally
      {
        if (fr != null)
        {
          try{fr.close();}catch(Exception e2){}
        }
      }
      try
      {
        fr = new java.io.BufferedReader(new java.io.FileReader("/sys/block/" + devName + "/size"));
        long size = Long.parseLong(fr.readLine()) / 2; // convert from 512 byte to 1k blocks
        if (size > 1024*1024)
          specialName += "(" + (size + 512*1024)/(1024*1024) + " GB)";
        else
          specialName += "(" + (size + 512)/1024 + " MB)";
      }
      catch (Exception e)
      {
        if (Sage.DBG) System.out.println("Error reading device size:" + e);
      }
      finally
      {
        if (fr != null)
        {
          try{fr.close();}catch(Exception e2){}
        }
      }
      // Check for partition numbering so we have unique names for multi-partition mounts
      char lastChar = name.charAt(name.length() - 1);
      if (Character.isDigit(lastChar) && lastChar != '1')
      {
        specialName += " (" + lastChar + ")";
      }
      specialName = specialName.trim();
      // Now get the serial # for this USB device
      String ueventData = IOUtils.getFileAsString(new java.io.File("/sys/block/" + devName + "/uevent"));
      String newSerial = "undefined";
      String oldSerial = "undefined";
      if (ueventData != null)
      {
        int idx = ueventData.indexOf("PHYSDEVPATH=");
        if (idx != -1)
        {
          String busPath = ueventData.substring(idx + 12);
          idx = busPath.indexOf("usb");
          if (idx != -1)
          {
            idx = busPath.indexOf('/', idx + 1);
            if (idx != -1)
            {
              idx = busPath.indexOf('/', idx + 1);
              if (idx != -1)
              {
                busPath = busPath.substring(0, idx + 1);
                java.io.File serialFile = new java.io.File("/sys" + busPath + "serial");
                if (serialFile.isFile())
                  oldSerial = IOUtils.getFileAsString(serialFile);
                // Use the udev HDD serial technique
                newSerial = IOUtils.exec(new String[] { "./vol_id", "-u",
                    "/dev/" + (name.endsWith("/") ? name.substring(0, name.length() - 1) : name) }, true, true);
                if (newSerial == null || newSerial.length() == 0)
                  newSerial = "undefined";
                else
                  newSerial = newSerial.trim();
                if (oldSerial != null)
                  oldSerial = oldSerial.trim();
              }
            }
          }
        }
      }
      //			if (Sage.DBG) System.out.println("User device name: " + specialName);
      mountTarget = getMountName(specialName, newSerial, oldSerial);
    }
    java.io.File newDir = new java.io.File(extDevRoot + "/external/" + mountTarget + "/");
    if (mountedDevs != null && mountedDevs.contains(name) && (!extraMountCheck || IOUtils.isExternalDriveMounted(name, newDir.toString())))
      accountedDevs.add(name);
    else
    {
      newDir.mkdirs();
      if (Sage.DBG) System.out.println("Setup dir for external device mount: " + newDir);
      if (IOUtils.mountExternalDrive(name, newDir.toString()))
      {
        if (mountedDevs != null && accountedDevs != null)
        {
          mountedDevs.add(name);
          accountedDevs.add(name);
          deviceMap.put(specialName, newDir);
          mountMap.put(name, newDir);
          if (seekerNotifies != null)
          {
            seekerNotifies.add(new Object[] { newDir, specialName });
          }
          else
          {
            if (!Sage.client && Seeker.getInstance().isAutoImportEnabled() && UIManager.getNonLocalUICount() == 0)
            {
              if (Sage.DBG) System.out.println("Automatically adding USB device to import path since no UI is available for prompting...");
              Seeker.getInstance().addArchiveDirectory(newDir.toString(), Seeker.ALL_DIR_MASK);
            }
            Catbert.distributeHookToLocalUIs("StorageDeviceAdded", new Object[] { newDir, specialName });
          }
        }
      }
      else
      {
        if (Sage.DBG) System.out.println("Failed mounting");
        if (mountedDevs != null && accountedDevs != null)
        {
          // So we don't try to keep remounting it over and over again
          mountedDevs.add(name);
          accountedDevs.add(name);
        }
      }
    }
  }

  protected void linuxRun()
  {
    // Check /dev/sdXX for new devices and when they show up then mount
    // them in the /var/media/external/sdXX paths.
    final String extDevRoot = Sage.EMBEDDED ? "/tmp" : SageTV.LINUX_ROOT_MEDIA_PATH;
    final java.util.Set mountedDevs = new java.util.HashSet();
    Runtime.getRuntime().addShutdownHook(new Thread()
    {
      public void run()
      {
        if (!mountedDevs.isEmpty())
        {
          if (Sage.DBG) System.out.println("Device cleanup, unmounting drives...");
          java.util.Iterator walker = mountedDevs.iterator();
          while (walker.hasNext())
          {
            String name = (String) walker.next();
            if (mountMap.get(name) != null)
            {
              IOUtils.exec2(new String[] { "umount", mountMap.get(name).toString() });
              // Remove the folder we created as well so it cleans up better on drive removal
              ((java.io.File) mountMap.get(name)).delete();
            }
          }
        }
      }
    });
    java.util.HashSet protectedDevices = new java.util.HashSet();
    String protectedStrs = Sage.get("linux/protected_devices", "");
    java.util.StringTokenizer toker = new java.util.StringTokenizer(protectedStrs, ",;");
    while (toker.hasMoreTokens())
      protectedDevices.add(toker.nextToken());

    // Find any other protected devices (i.e. sata/scsi drives)
    if (!Sage.EMBEDDED)
    {
      java.io.File[] devIdFiles = new java.io.File("/dev/disk/by-id").listFiles();
      for (int i = 0; devIdFiles != null && i < devIdFiles.length; i++)
      {
        try
        {
          if (devIdFiles[i].getName().startsWith("scsi"))
            protectedDevices.add(devIdFiles[i].getCanonicalFile().getName());
        }
        catch (java.io.IOException e)
        {
          if (Sage.DBG) System.out.println("Error resolving canon path:" + e);
        }
      }

      // Remove our USB boot drive if we're a NAS
      if (Sage.getBoolean("linux/enable_nas", false))
      {
        String usbBootDev = IOUtils.exec(new String[] { "tbutil", "findsig", "0x53414745"});
        if (usbBootDev != null)
        {
          usbBootDev = new java.io.File(usbBootDev).getName();
          if (Sage.DBG) System.out.println("Found USB boot device; adding it to protected list:" + usbBootDev);
          protectedDevices.add(usbBootDev);
          // Also add any partitions from the device
          String[] devFiles = new java.io.File("/dev").list();
          for (int i = 0; devFiles != null && i < devFiles.length; i++)
          {
            if (devFiles[i].startsWith(usbBootDev))
            {
              if (Sage.DBG) System.out.println("Found USB boot device partition; adding it to protected list:" + devFiles[i]);
              protectedDevices.add(devFiles[i]);
            }
          }
        }
      }
    }
    else
    {
      java.io.File[] devFiles = new java.io.File("/sys/block").listFiles();
      for (int i = 0; devFiles != null && i < devFiles.length; i++)
      {
        String name = devFiles[i].getName();
        if (name.startsWith("sd") && name.length() > 2)
        {
          // Check the device path inside of here to see if it's a SATA internal drive
          try
          {
            if (new java.io.File(devFiles[i], "device").getCanonicalPath().indexOf("SATA") != -1 ||
                new java.io.File(devFiles[i], "device").getCanonicalPath().indexOf("ahci") != -1)
            {
              if (Sage.DBG) System.out.println("Found SATA HDD to ignore in external device mount checking of: " + name);
              protectedDevices.add(name);
            }
          }
          catch (java.io.IOException ioe)
          {
            if (Sage.DBG) System.out.println("Error resolving canon path: " + ioe);
          }
        }
      }
    }
    java.util.Set accountedDevs = new java.util.HashSet();
    boolean extraMountCheck = false;
    while (true)
    {
      accountedDevs.clear();
      if (Sage.EMBEDDED)
      {
        int devMapSizeStart = deviceMap.size();
        java.io.File[] devFiles = new java.io.File("/sys/block").listFiles();
        for (int i = 0; devFiles != null && i < devFiles.length; i++)
        {
          String name = devFiles[i].getName();
          if (protectedDevices.contains(name))
            continue;
          if (name.startsWith("sd") && name.length() > 2)
          {
            boolean foundParts = false;
            java.io.File[] subFiles = devFiles[i].listFiles();
            for (int j = 0; subFiles != null && j < subFiles.length; j++)
            {
              name = subFiles[j].getName();
              if (name.startsWith("sd") && name.length() > 3)
              {
                foundParts = true;
                mountLinuxDevice(name, extDevRoot, mountedDevs, accountedDevs, extraMountCheck, null);
              }
            }
            if (!foundParts)
            {
              // For NTFS there's no partitions, only the device, so try to mount just the device
              mountLinuxDevice(devFiles[i].getName(), extDevRoot, mountedDevs, accountedDevs, extraMountCheck, null);
            }
          }
        }
        if (deviceMap.size() != devMapSizeStart && !Sage.client)
          LinuxUtils.updateSmbConfig(false);
      }
      else
      {
        java.io.File[] devFiles = new java.io.File("/dev").listFiles();
        for (int i = 0; devFiles != null && i < devFiles.length; i++)
        {
          String name = devFiles[i].getName();
          if (protectedDevices.contains(name))
            continue;
          if (name.startsWith("sd") && name.length() > 3)
          {
            mountLinuxDevice(name, extDevRoot, mountedDevs, accountedDevs, extraMountCheck, null);
          }
        }
      }
      mountedDevs.removeAll(accountedDevs);
      if (!mountedDevs.isEmpty())
      {
        if (Sage.DBG) System.out.println("External drives lost: " + mountedDevs);
        java.util.Set allArchiveDirs = (Seeker.getInstance().isAutoImportEnabled() && !Sage.client) ?
            new java.util.HashSet(java.util.Arrays.asList(Seeker.getInstance().getArchiveDirectories(Seeker.ALL_DIR_MASK))) : null;
            java.util.Iterator walker = mountedDevs.iterator();
            boolean validRemoves = false;
            while (walker.hasNext())
            {
              String name = (String) walker.next();
              java.io.File mountedDir = (java.io.File) mountMap.get(name);
              boolean umountOK = true;
              if (mountedDir != null)
              {
                umountOK = IOUtils.exec2(new String[] {"umount", mountedDir.toString() }) == 0;
                if (umountOK)
                {
                  // Remove the folder we created as well so it cleans up better on drive removal
                  deviceMap.values().remove(mountedDir);
                  mountedDir.delete();
                }
                else
                {
                  if (Sage.DBG) System.out.println("Unmounting of drive failed...retry again later...");
                  accountedDevs.add(name);
                }
              }
              if (umountOK)
              {
                validRemoves = true;
                mountMap.remove(name);
                if (allArchiveDirs != null && mountedDir != null && allArchiveDirs.contains(mountedDir))
                {
                  Seeker.getInstance().removeArchiveDirectory(mountedDir, Seeker.ALL_DIR_MASK);
                }
              }
            }
            if (validRemoves)
            {
              if (!Sage.client)
              {
                LinuxUtils.updateSmbConfig(true);
                Seeker.getInstance().scanLibrary(false);
              }
              extraMountCheck = true; // in case we unmounted something that changed device ID
            }
      }
      else
        extraMountCheck = false;
      mountedDevs.clear();
      mountedDevs.addAll(accountedDevs);
      try
      {
        Thread.sleep(Sage.getLong("linux/new_dev_scan_wait_period", 10000));
      }
      catch (Exception e)
      {}
    }
  }

  protected void macRun()
  {
    java.io.File volDir = new java.io.File("/Volumes");
    java.util.Set existingDrives = new java.util.HashSet(java.util.Arrays.asList(volDir.listFiles()));
    while (true)
    {
      java.util.Set currDrives = new java.util.HashSet(java.util.Arrays.asList(volDir.listFiles()));
      java.util.Set currDrivesClone = new java.util.HashSet(currDrives);
      currDrives.removeAll(existingDrives);

      java.util.Iterator walker = currDrives.iterator();
      while (walker.hasNext())
      {
        java.io.File newDrive = (java.io.File) walker.next();
        if (Sage.DBG) System.out.println("Detected new drive: " + newDrive);
        deviceMap.put(newDrive.toString(), newDrive);
        Catbert.distributeHookToLocalUIs("StorageDeviceAdded", new Object[] { newDrive, "" });
      }
      // If we lost any drives then run the library import scan again to clear out the temps
      existingDrives.removeAll(currDrivesClone);
      if (!existingDrives.isEmpty())
      {
        deviceMap.values().removeAll(existingDrives);
        if (Sage.DBG) System.out.println("Drives have been removed...rescan...");
        if (!Sage.client)
        {
          Seeker.getInstance().scanLibrary(false);
        }
      }
      existingDrives = currDrivesClone;
      try
      {
        Thread.sleep(Sage.getLong("macintosh/new_dev_scan_wait_period", 10000));
      }
      catch (Exception e)
      {}
    }
  }

  private String getMountName(String prefMountName, String serial, String oldSerial)
  {
    // We check our map to see if there's already something in there that uses this name/serial combo, and if so we just return that. If the name
    // is not used, then we store it in the map along with the serial and save that for later. If the name conflicts, then we add an index
    // counter to it until we get a unique name and store that.
    String existingSerial = nameSerialMap.getProperty(prefMountName);
    if (existingSerial != null && existingSerial.equals(serial))
      return prefMountName;
    else if (existingSerial != null && existingSerial.equals(oldSerial))
    {
      // Put it under the new serial instead in the property
      serialProp += "|" + serial + "," + prefMountName;
      nameSerialMap.setProperty(prefMountName, serial);
      Sage.put("linux/usb_name_serial_map", serialProp);
      if (Sage.DBG) System.out.println("Established serial number of " + serial + " for USB mount path " + prefMountName + " and removed old serial mapping from " + oldSerial);
      return prefMountName;
    }

    if (existingSerial == null)
    {
      // Put it in the map and save the property
      serialProp += "|" + serial + "," + prefMountName;
      nameSerialMap.setProperty(prefMountName, serial);
      Sage.put("linux/usb_name_serial_map", serialProp);
      if (Sage.DBG) System.out.println("Established serial number of " + serial + " for USB mount path " + prefMountName);
      return prefMountName;
    }

    int counter = 2;
    String newMountName;
    while (true)
    {
      newMountName = prefMountName + "-" + counter;
      existingSerial = nameSerialMap.getProperty(newMountName);
      if (existingSerial != null && existingSerial.equals(serial))
        return newMountName;
      else if (existingSerial != null && existingSerial.equals(oldSerial))
      {
        // Put it under the new serial instead in the property
        serialProp += "|" + serial + "," + prefMountName;
        nameSerialMap.setProperty(prefMountName, serial);
        Sage.put("linux/usb_name_serial_map", serialProp);
        if (Sage.DBG) System.out.println("Established serial number of " + serial + " for USB mount path " + prefMountName + " and removed old serial mapping from " + oldSerial);
        return prefMountName;
      }

      if (existingSerial == null)
      {
        // Put it in the map and save the property
        serialProp += "|" + serial + "," + newMountName;
        nameSerialMap.setProperty(newMountName, serial);
        Sage.put("linux/usb_name_serial_map", serialProp);
        if (Sage.DBG) System.out.println("Established serial number of " + serial + " for USB mount path " + newMountName + " (original was " + prefMountName + ")");
        return newMountName;
      }
      counter++;
    }
  }

  private java.util.Map deviceMap;
  private java.util.Map mountMap;
  private java.util.Properties nameSerialMap;
  private String serialProp;
}

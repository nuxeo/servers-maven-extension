{
  description = "A Nix-flake-based Java development environment";

  inputs = {
    # Package sets
    nixpkgs-master.url = "github:NixOS/nixpkgs/master";
    nixpkgs-stable.url = "github:NixOS/nixpkgs/nixpkgs-22.11-darwin";
    nixpkgs-unstable.url = "github:nixos/nixpkgs/nixpkgs-unstable";

    # Flake utilities
    flake-compat = { url = "github:edolstra/flake-compat"; flake = false; };
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, flake-utils, ... }@inputs:
    let
      inherit (self.lib) attrValues makeOverridable optionalAttrs singleton;

      nixpkgsDefaults = {
        config = {
          allowUnfree = true;
        };
      };
    in {
      lib = inputs.nixpkgs-unstable.lib;

      overlays = {
        pkgs-master = _: prev: {
          pkgs-release = import inputs.nixpkgs-master {
            inherit (prev.stdenv) system;
            inherit (nixpkgsDefaults) config;
          };
        };
        pkgs-stable = _: prev: {
          pkgs-release = import inputs.nixpkgs-stable {
            inherit (prev.stdenv) system;
            inherit (nixpkgsDefaults) config;
          };
        };
        pkgs-unstable = _: prev: {
          pkgs-unstable = import inputs.nixpkgs-unstable {
            inherit (prev.stdenv) system;
            inherit (nixpkgsDefaults) config;
          };
        };
      };
    } // flake-utils.lib.eachDefaultSystem (system: {
      stable-packages = import inputs.nixpkgs-stable (nixpkgsDefaults // { inherit system; });
      unstable-packages = import inputs.nixpkgs-unstable (nixpkgsDefaults // { inherit system; });
      devShells = let
        stable-pkgs = self.stable-packages.${system};
        unstable-pkgs = self.unstable-packages.${system};
      in {
        default = stable-pkgs.mkShell {
          packages = with stable-pkgs; [
            # jdk
            jdk11
            # groovy
            groovy
          ];
        };
      };
    });
}
